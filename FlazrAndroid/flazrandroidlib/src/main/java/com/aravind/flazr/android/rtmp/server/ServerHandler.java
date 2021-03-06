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

package com.aravind.flazr.android.rtmp.server;

import com.aravind.flazr.android.rtmp.RtmpMessage;
import com.aravind.flazr.android.rtmp.RtmpPublisher;
import com.aravind.flazr.android.rtmp.RtmpReader;
import com.aravind.flazr.android.rtmp.RtmpWriter;
import com.aravind.flazr.android.rtmp.message.Audio;
import com.aravind.flazr.android.rtmp.message.BytesRead;
import com.aravind.flazr.android.rtmp.message.ChunkSize;
import com.aravind.flazr.android.rtmp.message.Command;
import com.aravind.flazr.android.rtmp.message.Control;
import com.aravind.flazr.android.rtmp.message.DataMessage;
import com.aravind.flazr.android.rtmp.message.Metadata;
import com.aravind.flazr.android.rtmp.message.SetPeerBw;
import com.aravind.flazr.android.rtmp.message.Video;
import com.aravind.flazr.android.rtmp.message.WindowAckSize;
import com.aravind.flazr.android.util.ChannelUtils;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.WriteCompletionEvent;
import org.jboss.netty.channel.group.ChannelGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@ChannelPipelineCoverage("one")
public class ServerHandler extends SimpleChannelHandler {

    private static final Logger logger = LoggerFactory.getLogger(ServerHandler.class.getSimpleName());

    private int bytesReadWindow = 2500000;
    private long bytesRead;
    private long bytesReadLastSent;

    private long bytesWritten;
    private int bytesWrittenWindow = 2500000;
    private int bytesWrittenLastReceived;

    private ServerApplication application;
    private String clientId;
    private String playName;
    private int streamId;
    private int bufferDuration;

    private RtmpPublisher publisher;
    private ServerStream subscriberStream;
    private RtmpWriter recorder;

    private boolean aggregateModeEnabled = true;

    public void setAggregateModeEnabled(boolean aggregateModeEnabled) {
        this.aggregateModeEnabled = aggregateModeEnabled;
    }

    @Override
    public void channelOpen(final ChannelHandlerContext ctx, final ChannelStateEvent e) {
        RtmpServer.CHANNELS.add(e.getChannel());
        logger.info("opened channel: {}", e);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final ExceptionEvent e) {
        ChannelUtils.exceptionCaught(e);
    }

    @Override
    public void channelClosed(final ChannelHandlerContext ctx, final ChannelStateEvent e) {
        logger.info("channel closed: {}", e);
        if (publisher != null) {
            publisher.close();
        }
        if (recorder != null) {
            recorder.close();
        }
        unpublishIfLive();
    }

    @Override
    public void writeComplete(final ChannelHandlerContext ctx, final WriteCompletionEvent e) throws Exception {
        bytesWritten += e.getWrittenAmount();
        super.writeComplete(ctx, e);
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent me) {
        if (publisher != null && publisher.handle(me)) {
            return;
        }
        final Channel channel = me.getChannel();
        final RtmpMessage message = (RtmpMessage) me.getMessage();
        bytesRead += message.getHeader().getSize();
        if ((bytesRead - bytesReadLastSent) > bytesReadWindow) {
            logger.info("sending bytes read ack after: {}", bytesRead);
            BytesRead ack = new BytesRead(bytesRead);
            channel.write(ack);
            bytesReadLastSent = bytesRead;
        }
        switch (message.getHeader().getMessageType()) {
            case CHUNK_SIZE: // handled by decoder
                break;
            case CONTROL:
                final Control control = (Control) message;
                switch (control.getType()) {
                    case SET_BUFFER:
                        logger.debug("received set buffer: {}", control);
                        bufferDuration = control.getBufferLength();
                        if (publisher != null) {
                            publisher.setBufferDuration(bufferDuration);
                        }
                        break;
                    default:
                        logger.info("ignored control: {}", control);
                }
                break;
            case COMMAND_AMF0:
            case COMMAND_AMF3:
                final Command command = (Command) message;
                final String name = command.getName();
                if (name.equals("connect")) {
                    connectResponse(channel, command);
                } else if (name.equals("createStream")) {
                    streamId = 1;
                    channel.write(Command.createStreamSuccess(command.getTransactionId(), streamId));
                } else if (name.equals("play")) {
                    playResponse(channel, command);
                } else if (name.equals("deleteStream")) {
                    int deleteStreamId = ((Double) command.getArg(0)).intValue();
                    logger.info("deleting stream id: {}", deleteStreamId);
                    // TODO ?
                } else if (name.equals("closeStream")) {
                    final int clientStreamId = command.getHeader().getStreamId();
                    logger.info("closing stream id: {}", clientStreamId); // TODO
                    unpublishIfLive();
                } else if (name.equals("pause")) {
                    pauseResponse(channel, command);
                } else if (name.equals("seek")) {
                    seekResponse(channel, command);
                } else if (name.equals("publish")) {
                    publishResponse(channel, command);
                } else {
                    logger.warn("ignoring command: {}", command);
                    fireNext(channel);
                }
                return; // NOT break
            case METADATA_AMF0:
            case METADATA_AMF3:
                final Metadata meta = (Metadata) message;
                if (meta.getName().equals("onMetaData")) {
                    logger.info("adding onMetaData message: {}", meta);
                    meta.setDuration(-1);
                    subscriberStream.addConfigMessage(meta);
                }
                broadcast(message);
                break;
            case AUDIO:
            case VIDEO:
                if (((DataMessage) message).isConfig()) {
                    logger.info("adding config message: {}", message);
                    subscriberStream.addConfigMessage(message);
                }
            case AGGREGATE:
                broadcast(message);
                break;
            case BYTES_READ:
                final BytesRead bytesReadByClient = (BytesRead) message;
                bytesWrittenLastReceived = bytesReadByClient.getValue();
                logger.debug("bytes read ack from client: {}, actual: {}", bytesReadByClient, bytesWritten);
                break;
            case WINDOW_ACK_SIZE:
                WindowAckSize was = (WindowAckSize) message;
                if (was.getValue() != bytesReadWindow) {
                    channel.write(SetPeerBw.dynamic(bytesReadWindow));
                }
                break;
            case SET_PEER_BW:
                SetPeerBw spb = (SetPeerBw) message;
                if (spb.getValue() != bytesWrittenWindow) {
                    channel.write(new WindowAckSize(bytesWrittenWindow));
                }
                break;
            default:
                logger.warn("ignoring message: {}", message);
        }
        fireNext(channel);
    }

    private void fireNext(final Channel channel) {
        if (publisher != null && publisher.isStarted() && !publisher.isPaused()) {
            publisher.fireNext(channel, 0);
        }
    }

    //==========================================================================

    private RtmpMessage[] getStartMessages(final RtmpMessage variation) {
        final List<RtmpMessage> list = new ArrayList<RtmpMessage>();
        list.add(new ChunkSize(4096));
        list.add(Control.streamIsRecorded(streamId));
        list.add(Control.streamBegin(streamId));
        if (variation != null) {
            list.add(variation);
        }
        list.add(Command.playStart(playName, clientId));
        list.add(Metadata.rtmpSampleAccess());
        list.add(Audio.empty());
        list.add(Metadata.dataStart());
        return list.toArray(new RtmpMessage[list.size()]);
    }

    private void broadcast(final RtmpMessage message) {
        if (subscriberStream.getSubscribers() != null) {
            subscriberStream.getSubscribers().write(message);
        }
        if (recorder != null) {
            recorder.write(message);
        }
    }

    private void writeToStream(final Channel channel, final RtmpMessage message) {
        if (message.getHeader().getChannelId() > 2) {
            message.getHeader().setStreamId(streamId);
        }
        channel.write(message);
    }

    //==========================================================================

    private void connectResponse(final Channel channel, final Command connect) {
        final String appName = (String) connect.getObject().get("app");
        clientId = channel.getId() + "";
        application = ServerApplication.get(appName); // TODO auth, validation
        logger.info("connect, client id: {}, application: {}", clientId, application);
        channel.write(new WindowAckSize(bytesWrittenWindow));
        channel.write(SetPeerBw.dynamic(bytesReadWindow));
        channel.write(Control.streamBegin(streamId));
        final Command result = Command.connectSuccess(connect.getTransactionId());
        channel.write(result);
        channel.write(Command.onBWDone());
    }

    private void playResponse(final Channel channel, final Command play) {
        int playStart = -2;
        int playLength = -1;
        if (play.getArgCount() > 1) {
            playStart = ((Double) play.getArg(1)).intValue();
        }
        if (play.getArgCount() > 2) {
            playLength = ((Double) play.getArg(2)).intValue();
        }
        final boolean playReset;
        if (play.getArgCount() > 3) {
            playReset = ((Boolean) play.getArg(3));
        } else {
            playReset = true;
        }
        final Command playResetCommand = playReset ? Command.playReset(playName, clientId) : null;
        final String clientPlayName = (String) play.getArg(0);
        final ServerStream stream = application.getStream(clientPlayName);
        logger.debug("play name {}, start {}, length {}, reset {}",
                clientPlayName, playStart, playLength, playReset);
        if (stream.isLive()) {
            for (final RtmpMessage message : getStartMessages(playResetCommand)) {
                writeToStream(channel, message);
            }
            boolean videoConfigPresent = false;
            try {
                final List<RtmpMessage> list = stream.getConfigMessages();
                if(list != null) {
                    synchronized (list) {
                        Iterator<RtmpMessage> i = list.iterator(); // Must be in synchronized block
                        while (i.hasNext()) {
                            RtmpMessage message = i.next();
                            logger.info("writing start meta / config: {}", message);
                            if (message.getHeader().isVideo()) {
                                videoConfigPresent = true;
                            }
                            writeToStream(channel, message);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (!videoConfigPresent) {
                writeToStream(channel, Video.empty());
            }
            if(stream.getSubscribers()!= null) {
                stream.getSubscribers().add(channel);
            }
            logger.info("client requested live stream: {}, added to stream: {}", clientPlayName, stream);
            return;
        }
        if (!clientPlayName.equals(playName)) {
            playName = clientPlayName;
            final RtmpReader reader = application.getReader(playName);
            if (reader == null) {
                channel.write(Command.playFailed(playName, clientId));
                return;
            }
            publisher = new RtmpPublisher(reader, streamId, bufferDuration, true, aggregateModeEnabled) {
                @Override
                protected RtmpMessage[] getStopMessages(long timePosition) {
                    return new RtmpMessage[]{
                            Metadata.onPlayStatus(timePosition / 1000, bytesWritten),
                            Command.playStop(playName, clientId),
                            Control.streamEof(streamId)
                    };
                }
            };
        }
        publisher.start(channel, playStart, playLength, getStartMessages(playResetCommand));
    }

    private void pauseResponse(final Channel channel, final Command command) {
        if (publisher == null) {
            logger.debug("cannot pause when live");
            return;
        }
        final boolean paused = ((Boolean) command.getArg(0));
        final int clientTimePosition = ((Double) command.getArg(1)).intValue();
        logger.debug("pause request: {}, client time position: {}", paused, clientTimePosition);
        if (!paused) {
            logger.debug("doing unpause, seeking and playing");
            final Command unpause = Command.unpauseNotify(playName, clientId);
            publisher.start(channel, clientTimePosition, getStartMessages(unpause));
        } else {
            publisher.pause();
        }
    }

    private void seekResponse(final Channel channel, final Command command) {
        if (publisher == null) {
            logger.debug("cannot seek when live");
            return;
        }
        final int clientTimePosition = ((Double) command.getArg(0)).intValue();
        if (!publisher.isPaused()) {
            final Command seekNotify = Command.seekNotify(streamId, clientTimePosition, playName, clientId);
            publisher.start(channel, clientTimePosition, getStartMessages(seekNotify));
        } else {
            logger.debug("ignoring seek when paused, client time position: {}", clientTimePosition);
        }
    }

    private void publishResponse(final Channel channel, final Command command) {
        if (command.getArgCount() > 1) { // publish
            final String streamName = (String) command.getArg(0);
            final String publishTypeString = (String) command.getArg(1);
            logger.info("publish, stream name: {}, type: {}", streamName, publishTypeString);
            subscriberStream = application.getStream(streamName, publishTypeString); // TODO append, record
            if (subscriberStream.getPublisher() != null) {
                logger.info("disconnecting publisher client, stream already in use");
//                ChannelFuture future = channel.write(Command.publishBadName(streamId));
//                future.addListener(ChannelFutureListener.CLOSE);
//                return;
            }
            subscriberStream.setPublisher(channel);
            channel.write(Command.publishStart(streamName, clientId, streamId));
            channel.write(new ChunkSize(4096));
            channel.write(Control.streamBegin(streamId));
            final ServerStream.PublishType publishType = subscriberStream.getPublishType();
            if (publishType != null) {
                logger.info("created publish stream: {}", subscriberStream);
                switch (publishType) {
                    case LIVE:
                        final ChannelGroup subscribers = subscriberStream.getSubscribers();
                        if (subscribers != null) {
                            subscribers.write(Command.publishNotify(streamId));
                            writeToStream(subscribers, Video.empty());
                            writeToStream(subscribers, Metadata.rtmpSampleAccess());
                            writeToStream(subscribers, Audio.empty());
                            writeToStream(subscribers, Metadata.dataStart());
                        }
                        break;
                    case RECORD:
                        recorder = application.getWriter(streamName);
                        break;
                    case APPEND:
                        logger.warn("append not implemented yet, un-publishing...");
                        unpublishIfLive();
                        break;
                }
            }
        } else { // un-publish
            final boolean publish = (Boolean) command.getArg(0);
            if (!publish) {
                unpublishIfLive();
            }
        }
    }

    // TODO cleanup
    private void writeToStream(final ChannelGroup channelGroup, final RtmpMessage message) {
        if (message.getHeader().getChannelId() > 2) {
            message.getHeader().setStreamId(streamId);
        }
        channelGroup.write(message);
    }

    private void unpublishIfLive() {
        if (subscriberStream != null && subscriberStream.getPublisher() != null) {
            final Channel channel = subscriberStream.getPublisher();
            if (channel.isWritable()) {
                channel.write(Command.unpublishSuccess(subscriberStream.getName(), clientId, streamId));
            }
            if (subscriberStream.getSubscribers() != null) {
                subscriberStream.getSubscribers().write(Command.unpublishNotify(streamId));
            }
            subscriberStream.setPublisher(null);
            logger.debug("publisher disconnected, stream un-published");
        }
        if (recorder != null) {
            recorder.close();
            recorder = null;
        }
    }

}
