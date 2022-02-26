/*
 * Copyright (C) 2016-2022 phantombot.github.io/PhantomBot
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package tv.phantombot.twitch.irc;

import com.gmt2001.ExponentialBackoff;
import java.net.URI;
import java.nio.channels.NotYetConnectedException;
import java.util.Date;
import java.util.concurrent.locks.ReentrantLock;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import tv.phantombot.PhantomBot;
import tv.phantombot.twitch.api.TwitchValidate;
import tv.phantombot.twitch.irc.chat.utils.Message;
import tv.phantombot.twitch.irc.chat.utils.MessageQueue;

public class TwitchSession extends MessageQueue {

    private final String botName;
    private String oAuth;
    private TwitchWSIRC twitchWSIRC;
    private final ReentrantLock reconnectLock = new ReentrantLock();
    private final ExponentialBackoff backoff = new ExponentialBackoff(5L, 300000L);

    /**
     * Class constructor.
     *
     * @param {String} channelName
     * @param {String} botName
     * @param {String} oAuth
     */
    public TwitchSession(String channelName, String botName, String oAuth) {
        super(channelName);
        this.botName = botName;
        this.oAuth = oAuth;
    }

    public void doSubscribe() {
        this.subscribe(this);
    }

    /**
     * Method that returns the channel name
     *
     * @return {String} channelName
     */
    public String getChannelName() {
        return this.channelName;
    }

    public void setOAuth(String oAuth) {
        this.oAuth = oAuth;
        this.twitchWSIRC.setOAuth(oAuth);
    }

    /**
     * Method that returns the bot name.
     *
     * @return {String} botName
     */
    public String getBotName() {
        return this.botName;
    }

    /**
     * Method that sends a raw message to the socket.
     *
     * @param {String} message
     */
    public void sendRaw(String message) {
        this.sendRaw(message, false);
    }

    private void sendRaw(String message, boolean isretry) {
        try {
            this.twitchWSIRC.send(message);
            this.backoff.Reset();
        } catch (NotYetConnectedException ex) {
            if (!isretry) {
                try {
                    com.gmt2001.Console.warn.println("Tried to send message before connecting to Twitch, trying again in 5 seconds...");
                    Thread.sleep(5000);
                    this.sendRaw(message, true);
                    return;
                } catch (InterruptedException ex2) {
                }
            }
            com.gmt2001.Console.err.println("Failed to send message to Twitch [NotYetConnectedException]: " + ex.getMessage());
        } catch (WebsocketNotConnectedException ex) {
            if (!isretry) {
                com.gmt2001.Console.warn.println("Tried to send message after connection to Twitch dropped, trying to reconnect...");
                this.reconnect();
                this.sendRaw(message, true);
                return;
            }
            com.gmt2001.Console.err.println("Failed to send message to Twitch [WebsocketNotConnectedException]: " + ex.getMessage());
        } catch (Exception ex) {
            com.gmt2001.Console.err.println("Failed to send message to Twitch [" + ex.getClass().getSimpleName() + "]: " + ex.getMessage());
        }
    }

    /**
     * Method that sends channel message.
     *
     * @param {String} message
     */
    public void send(String message) {
        this.sendRaw("PRIVMSG #" + this.getChannelName() + " :" + message);
    }

    /**
     * Method that will do the moderation check of the bot.
     */
    public void getModerationStatus() {
        this.send(".mods");
    }

    /**
     * Method that creates a connection with Twitch.
     */
    public TwitchSession connect() {
        // Connect to Twitch.
        try {
            this.twitchWSIRC = new TwitchWSIRC(new URI("wss://irc-ws.chat.twitch.tv"), this.channelName, this.botName, this.oAuth, this);
            if (!this.twitchWSIRC.connectWSS(false)) {
                throw new Exception("Error when connecting to Twitch.");
            }
        } catch (Exception ex) {
            com.gmt2001.Console.err.println("Failed to create a new TwitchWSIRC instance: " + ex.getMessage());
        }
        return this;
    }

    /**
     * Method that handles reconnecting with Twitch.
     */
    public void reconnect() {
        // Do not try to send messages anymore.
        this.setAllowSendMessages(false);
        if (PhantomBot.instance().isExiting()) {
            return;
        }

        if (this.reconnectLock.tryLock()) {
            try {
                com.gmt2001.Console.out.println("Delaying next connection attempt to prevent spam, " + (this.backoff.GetNextInterval() / 1000) + " seconds...");
                com.gmt2001.Console.warn.println("Delaying next reconnect " + (this.backoff.GetNextInterval() / 1000) + " seconds...", true);
                this.backoff.Backoff();

                this.quitIRC();
                Thread.sleep(500);
                this.connect();
                Thread.sleep(500);
                // Should be connected now.
                this.setAllowSendMessages(true);
            } catch (InterruptedException ex) {
                com.gmt2001.Console.err.printStackTrace(ex);
            } finally {
                this.reconnectLock.unlock();
            }
        }
    }

    @Override
    public void onNext(Message message) {
        double limit = PhantomBot.getMessageLimit();

        try {
            // Set the time we got the message.
            long time = System.currentTimeMillis();

            // Make sure we're allowed to send messages and that this one can be sent.
            if (this.isAllowedToSend && (this.nextWrite < time || (message.hasPriority() && this.writes <= 99))) {
                if (this.lastWrite > time) {
                    if (this.writes >= limit && !message.hasPriority()) {
                        this.nextWrite = (time + (this.lastWrite - time));
                        com.gmt2001.Console.warn.println("Message limit of (" + limit + ") has been reached. Messages will be sent again in " + (this.nextWrite - time) + "ms");
                        Thread.sleep(this.nextWrite - time);
                    }
                    this.writes++;
                } else {
                    this.writes = 1;
                    this.lastWrite = (time + 30200);
                }

                // Send the message.
                this.sendRaw("PRIVMSG #" + this.channelName + " :" + message.getMessage());
                com.gmt2001.Console.out.println("[CHAT] " + message.getMessage());
            }

            if (new Date().after(this.nextReminder)) {
                if ((!this.isAllowedToSend || TwitchValidate.instance().hasOAuthInconsistencies(PhantomBot.instance().getBotName()))) {
                    com.gmt2001.Console.warn.println("WARNING: Unable to send last message due to configuration error");

                    TwitchValidate.instance().checkOAuthInconsistencies(PhantomBot.instance().getBotName());

                    if (!this.isAllowedToSend) {
                        com.gmt2001.Console.warn.println("WARNING: May not be a moderator");
                    }
                }

                this.nextReminder.setTime(new Date().getTime() + REMINDER_INTERVAL);
            }
        } catch (InterruptedException ex) {
            com.gmt2001.Console.err.printStackTrace(ex);
        }
        this.subscription.request(1);
    }

    @Override
    public void onComplete() {
        this.close();
    }

    public void quitIRC() {
        // Send quit command to Twitch to exit correctly.
        this.sendRaw("QUIT");

        try {
            Thread.sleep(250);
        } catch (InterruptedException ex) {
        }
        // Close connection.
        this.twitchWSIRC.close(1000, "bye");
    }

    /**
     * Method that stops everything for TwitchWSIRC, there's no going back after this.
     */
    @Override
    public void close() {
        // Kill the message queue.
        this.kill();

        this.quitIRC();

        super.close();
    }
}
