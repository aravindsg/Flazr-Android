/*
 * Flazr <http://flazr.com> Copyright (C) 2009  Peter Thomas.
 *
 * This file is part of Flazr.
 *
 * Flazr is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Flazr is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Flazr.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.flazr.rtmp.client;

import com.flazr.io.flv.FlvWriter;
import static com.flazr.rtmp.message.Control.Type.*;

import com.flazr.rtmp.message.Control;
import com.flazr.rtmp.RtmpMessage;
import com.flazr.rtmp.RtmpPublisher;
import com.flazr.rtmp.message.BytesRead;
import com.flazr.rtmp.message.ChunkSize;
import com.flazr.rtmp.message.WindowAckSize;
import com.flazr.rtmp.message.Command;
import com.flazr.rtmp.message.Metadata;
import com.flazr.rtmp.message.SetPeerBw;
import com.flazr.util.Utils;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.HashMap;
import java.util.Map;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelPipelineCoverage("one")
public class ClientHandler extends SimpleChannelUpstreamHandler {

    private static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);

    private int transactionId = 1;
    private Map<Integer, String> transactionToCommandMap;
    private ClientSession session;
    private byte[] swfvBytes;

    private FlvWriter writer;

    private int bytesReadWindow = 2500000;
    private long bytesRead;
    private long bytesReadLastSent;    
    private int bytesWrittenWindow = 2500000;

    private Timer timer;
    private RtmpPublisher publisher;
    private int streamId;

    public void setSwfvBytes(byte[] swfvBytes) {
        this.swfvBytes = swfvBytes;        
        logger.info("set swf verification bytes: {}", Utils.toHex(swfvBytes));        
    }

    public ClientHandler(ClientSession session) {
        this.session = session;
        transactionToCommandMap = new HashMap<Integer, String>();        
    }

    private void writeCommandExpectingResult(Channel channel, Command command) {
        final int id = transactionId++;
        command.setTransactionId(id);
        transactionToCommandMap.put(id, command.getName());
        logger.info("sending command (expecting result): {}", command);
        channel.write(command);
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
        logger.info("handshake complete, sending 'connect'");
        writeCommandExpectingResult(e.getChannel(), Command.connect(session));
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        if(timer != null) {
            timer.stop();
        }
        if(writer != null) {
            writer.close();
        }
        if(publisher != null) {
            publisher.getReader().close();
        }
    }
    
    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent me) {
        if(publisher != null && publisher.handle(me)) {
            return;
        }
        final Channel channel = me.getChannel();
        final RtmpMessage message = (RtmpMessage) me.getMessage();
        switch(message.getHeader().getMessageType()) {
            case CONTROL:
                Control control = (Control) message;
                logger.debug("server control: {}", control);
                switch(control.getType()) {
                    case PING_REQUEST:
                        final int time = control.getTime();
                        logger.debug("server ping: {}", time);
                        Control pong = Control.pingResponse(time);
                        logger.debug("sending ping response: {}", pong);
                        channel.write(pong);
                        break;
                    case SWFV_REQUEST:
                        if(swfvBytes == null) {
                            logger.warn("swf verification not initialized!" 
                                + " not sending response, server likely to stop responding / disconnect");
                        } else {
                            Control swfv = Control.swfvResponse(swfvBytes);
                            logger.info("sending swf verification response: {}", swfv);
                            channel.write(swfv);
                        }
                        break;
                    case STREAM_BEGIN:
                        if(publisher != null) {
                            logger.info("publish mode, stream begin, will start {}", control);
                            publisher.start(channel, session.getPlayStart(),
                                    session.getPlayDuration(), new ChunkSize(4096));
                            return;
                        }
                        break;
                    default:
                        logger.debug("ignoring control message: {}", control);
                }
                break;
            case METADATA_AMF0:
            case METADATA_AMF3:
                Metadata metadata = (Metadata) message;
                if(metadata.getName().equals("onMetaData")) {
                    logger.info("writing server 'onMetaData': {}", metadata);
                    writer.write(message);
                } else {
                    logger.info("ignoring server metadata: {}", metadata);
                }
                break;
            case AUDIO:
            case VIDEO:
            case AGGREGATE:                
                writer.write(message);
                bytesRead += message.getHeader().getSize();
                if((bytesRead - bytesReadLastSent) > bytesReadWindow) {
                    logger.info("sending bytes read ack {}", bytesRead);
                    bytesReadLastSent = bytesRead;
                    channel.write(new BytesRead(bytesRead));
                }
                break;
            case COMMAND_AMF0:
            case COMMAND_AMF3:
                Command command = (Command) message;                
                String name = command.getName();
                logger.info("server command: {}", name);
                if(name.equals("_result")) {
                    String resultFor = transactionToCommandMap.get(command.getTransactionId());
                    logger.info("result for method call: {}", resultFor);
                    if(resultFor.equals("connect")) {
                        writeCommandExpectingResult(channel, Command.createStream());
                    } else if(resultFor.equals("createStream")) {
                        streamId = ((Double) command.getArg(0)).intValue();
                        logger.info("streamId to use: {}", streamId);
                        if(session.getType().isPublish()) {
                            timer = new HashedWheelTimer();                            
                            publisher = new RtmpPublisher(session.getReader(), timer, streamId) {
                                @Override protected RtmpMessage[] getStopMessages(long timePosition) {
                                    return new RtmpMessage[]{Command.unPublish(streamId)};
                                }
                            };                            
                            publisher.setTargetBufferDuration(200); // TODO cleanup
                            channel.write(Command.publish(streamId, session));
                            return;
                        } else {
                            writer = new FlvWriter(session.getPlayStart(), session.getSaveAs());
                            channel.write(Command.play(streamId, session));
                        }
                    } else {
                        logger.warn("un-handled server result for: {}", resultFor);
                    }
                } else if(name.equals("onStatus")) {
                    Map<String, Object> temp = (Map) command.getArg(0);
                    String code = (String) temp.get("code");
                    logger.info("onStatus code: {}", code);
                    if (code.equals("NetStream.Failed") || code.equals("NetStream.Play.Failed") || code.equals("NetStream.Play.Stop")) {
                        logger.info("disconnecting, bytes read: {}", bytesRead);                        
                        channel.close();
                    }
                    if (code.equals("NetStream.Unpublish.Success")) {
                        logger.info("unpublish success, closing channel");
                        ChannelFuture future = channel.write(Command.closeStream(streamId));
                        future.addListener(ChannelFutureListener.CLOSE);
                        return;
                    }
                } else {
                    logger.warn("ignoring server command: {}", command);
                }
                break;
            case BYTES_READ:
                logger.info("server bytes read: {}", message);
                break;
            case WINDOW_ACK_SIZE:
                WindowAckSize was = (WindowAckSize) message;                
                if(was.getValue() != bytesReadWindow) {
                    channel.write(SetPeerBw.dynamic(bytesReadWindow));
                }                
                break;
            case SET_PEER_BW:
                SetPeerBw spb = (SetPeerBw) message;                
                if(spb.getValue() != bytesWrittenWindow) {
                    channel.write(new WindowAckSize(bytesWrittenWindow));
                }
                break;
            default:
            logger.info("ignoring rtmp message: {}", message);
        }
        if(publisher != null) {
            publisher.write(channel);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
        if (e.getCause() instanceof ClosedChannelException) {
            logger.info("exception: {}", e);
        } else if(e.getCause() instanceof IOException) {
            logger.info("exception: {}", e.getCause().getMessage());
        } else {
            logger.warn("exception: {}", e.getCause());
        }
        if(e.getChannel().isOpen()) {
            e.getChannel().close();
        }
    }    

}