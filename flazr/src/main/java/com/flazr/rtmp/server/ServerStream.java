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
package com.flazr.rtmp.server;

import com.flazr.rtmp.RtmpMessage;
import com.flazr.util.Utils;
import java.util.ArrayList;
import java.util.List;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;

public class ServerStream {

    public static enum PublishType {

        LIVE,
        APPEND,
        RECORD;

        public String asString() {
            return this.name().toLowerCase();
        }

        public static PublishType parse(final String raw) {
            return PublishType.valueOf(raw.toUpperCase());
        }

    }

    private final String name;
    private final PublishType publishType;
    private final ChannelGroup subscribers;
    private final List<RtmpMessage> configMessages;

    public ServerStream(final String name) {
        this(name, null);
    }

    public ServerStream(final String rawName, final String typeString) {
        this.name = Utils.trimSlashes(rawName).toLowerCase();
        if(typeString != null) {
            this.publishType = PublishType.parse(typeString); // TODO record, append
            subscribers = new DefaultChannelGroup(name);
            configMessages = new ArrayList<RtmpMessage>();
        } else {
            this.publishType = null;
            subscribers = null;
            configMessages = null;
        }        
    }

    public boolean isLive() {
        return publishType != null && publishType == PublishType.LIVE;
    }

    public ChannelGroup getSubscribers() {
        return subscribers;
    }

    public String getName() {
        return name;
    }

    public List<RtmpMessage> getConfigMessages() {
        return configMessages;
    }

    public void addConfigMessage(final RtmpMessage message) {
        configMessages.add(message);
    }

}