package io.fire.core.client;

import io.fire.core.client.modules.request.ClientRequestModule;
import io.fire.core.client.modules.request.interfaces.ClientRequest;
import io.fire.core.client.modules.rest.RestModule;
import io.fire.core.client.modules.socket.SocketModule;
import io.fire.core.common.eventmanager.EventHandler;
import io.fire.core.common.eventmanager.enums.Event;
import io.fire.core.common.eventmanager.enums.EventPriority;
import io.fire.core.common.eventmanager.executors.EventExecutor;
import io.fire.core.common.interfaces.ClientMeta;
import io.fire.core.common.interfaces.Packet;
import io.fire.core.common.interfaces.PoolHolder;
import io.fire.core.common.interfaces.RequestBody;
import io.fire.core.common.io.enums.InstanceSide;
import io.fire.core.common.objects.ThreadPool;
import io.fire.core.common.objects.VersionInfo;
import io.fire.core.common.packets.ChannelMessagePacket;
import io.fire.core.common.packets.ChannelPacketPacket;

import io.fire.core.server.modules.client.superclasses.Client;
import lombok.Getter;

import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class FireIoClient implements PoolHolder {

    //modules + getters
    @Getter private SocketModule socketModule;
    @Getter private RestModule restModule;
    @Getter private EventHandler eventHandler = new EventHandler(InstanceSide.CLIENT);
    @Getter private ClientRequestModule clientRequestModule;

    //data we need in the client + some meta and connection arguments to use mid-handshake
    @Getter  private String host;
    @Getter private int port;
    private int connectAttampt = 0;
    private Timer scheduler = new Timer();
    private Map<String, String> connectionArguments = new HashMap<>();
    private Map<String, ClientMeta> connectionMeta = new HashMap<>();
    private ThreadPool pool = new ThreadPool();


    /**
     * Main FireIoClient
     *
     * Takes the host and port of a server
     * This is where the real party starts!
     * or the nightmare, depending on what you think of networking
     *
     * @param host
     * @param port
     */
    public FireIoClient(String host, int port) {
        //constructor! create the client!
        //register variables
        this.port = port;
        this.host = host;

        //register and start modules
        restModule = new RestModule(this);
        clientRequestModule = new ClientRequestModule(this);

        //register a listener for the connect event to reset attempt count every time a connection is made
        on(Event.CONNECT, (a-> connectAttampt = 0));
    }


    /**
     * Set the password to connect to the server
     * only has effect before the connect() function is called
     *
     * @param password
     * @return
     */
    public FireIoClient setPassword(String password) {
        //set password
        //overwrites the default null value
        restModule.setPassword(password);
        return this;
    }


    /**
     * Sets and enables the auto reconnect for the client
     * This means that when the connection dies or gets disrupted the client will try to re-connect after a set amount of milliseconds
     *
     * @param timeout in milliseconds
     * @return
     */
    public FireIoClient setAutoReConnect(int timeout) {
        //enable auto reconnect!
        //register a event to detect when the connection closed
        on(Event.TIMED_OUT, (client, string) -> {
            //parse the even payload (error message in this case)

            //fire disconnect event
            getEventHandler().triggerEvent(Event.DISCONNECT, null, "Disconnected.. attempting reconnect in " + timeout + "MS.");

            //log information about problem
            System.err.println("[Fire-IO] Connection closed unexpectedly! attempting re-connect in " + timeout + "MS.");
            System.err.println(" - Error: " + "connection could");
            connectAttampt++;
            System.err.println(" - Attempt: " + string);

            //retry after a given amount of milliseconds, if it fails this event will be triggered again creating a loop until a new connection was successful
            scheduler.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            connect();
                        }
                    }, timeout);
        });

        return this;
    }


    /**
     * Set a tag, this can be used by the server API to identify clients
     * works like a username
     *
     * @param s
     * @param b
     * @return
     */
    public FireIoClient setTag(String s, String b) {
        //set parameter!
        //can be used for custom authentication, can be accessed by the server before the handshake is finished
        connectionArguments.put(s, b);
        return this;
    }


    /**
     * Set a meta data tag
     * can be used to supply extra information mid handshake
     * (like client location or device information)
     *
     * @param s
     * @param meta
     * @return
     */
    public FireIoClient setMeta(String s, ClientMeta meta) {
        //set meta!
        //can be used to send client metadata mid handshake, can be accessed by the server before the handshake is finished
        connectionMeta.put(s, meta);
        return this;
    }


    /**
     * Set the thread pool size used to execute events
     * All events are ASYNC by default
     *
     * default is 1
     *
     * @param size
     * @return
     */
    public FireIoClient setThreadPoolSize(int size) {
        pool.setSize(size);
        return this;
    }

    /**
     * Connect function
     * this takes all default or set information about the client and attempts to connect and start a connection with the server
     *
     * @return
     */
    public FireIoClient connect() {
        //check for excisting connection
        if (socketModule != null) {
            socketModule.getConnection().close();
        }

        //connect to the server!
        System.out.println("[Fire-IO] starting client & requesting token");

        //request a new token via the http module!
        String a = restModule.initiateHandshake();
        if (a == null) {
            //could not get api key due to a connection problem
            //trigger event and trigger auto reconnect if set
            getEventHandler().triggerEvent(Event.TIMED_OUT, null, "Failed to get api key");
            return this;
        }

        if (a.startsWith("redirect=")) {
            a = a.replace("redirect=", "");
            String[] redirected = a.split("INFO:")[0].split(":");
            System.out.println("[Fire-IO] Loadbalancer redirected me to " + redirected[0] + ":" + redirected[1]);
            String rehost = redirected[0];
            int report = Integer.valueOf(redirected[1]);
            this.host = rehost;
            this.port = report;
            connect();
            return this;
        }

        if (a.equals("ratelimit")) {
            //ratifier blocked our request! we might have been creating to many new identities in a too short amount of time
            //trigger event and trigger auto reconnect if set
            getEventHandler().triggerEvent(Event.TIMED_OUT, null, "Connection blocked by ratelimiter");
            return this;
        }

        if (a.equals("fail-auth")) {
            //failed to authenticate! we wither did not give a password or did not use the correct password!
            //trigger event and trigger auto reconnect if set
            getEventHandler().triggerEvent(Event.TIMED_OUT, null, "Failed to authenticate, is your password correct?");
            return this;
        }

        //check if it is safe to use the new and updated handshake
        UUID assignedId = null;
        if (a.length() == 36) {
            //yes, it is 36 chars so only a uuid, fall back to the "old" method
            //we've got a id to use! parse it and set it as our own
            assignedId = UUID.fromString(a);
            if (assignedId == null) {
                //end the party here, it's an invalid id or unexpected result
                //trigger event and trigger auto reconnect if set
                getEventHandler().triggerEvent(Event.TIMED_OUT, null,"Failed to parse api key");
                return this;
            }
        } else if (a.length() > 36) {
            //no its new!
            //split the UUID and parse the server info!
            String[] elements = a.split("INFO:");
            assignedId = UUID.fromString(elements[0]);
            if (assignedId == null) {
                //end the party here, it's an invalid id or unexpected result
                //trigger event and trigger auto reconnect if set
                getEventHandler().triggerEvent(Event.TIMED_OUT, null,"Failed to parse api key");
                return this;
            }

            VersionInfo serverVersion = new VersionInfo().fromString(elements[1]);
            VersionInfo clientVersion = new VersionInfo();

            //run critical checks!
            if (!serverVersion.getRelease()) System.out.println("[Fire-IO] Warning: the server is running a pre-release build.");
            if (serverVersion.getCoreVersion() > clientVersion.getCoreVersion()) System.out.println("[Fire-IO] Warning: this client is out dated! server is running:"+serverVersion.getCoreVersion() + " and the client is running:"+clientVersion.getCoreVersion());
            if (serverVersion.getCoreVersion() < clientVersion.getCoreVersion()) System.out.println("[Fire-IO] Warning: this server is out dated! server is running:"+serverVersion.getCoreVersion() + " and the client is running:"+clientVersion.getCoreVersion());
            if (serverVersion.getProtocolVersion() > clientVersion.getProtocolVersion()) System.out.println("[Fire-IO] Warning: there is a version miss-match between the server and the client! the server is running a newer version, Fire-IO may not work as expected or fail entirely. Please update your server to release " + clientVersion.getCoreVersion());
            if (serverVersion.getProtocolVersion() < clientVersion.getProtocolVersion()) System.out.println("[Fire-IO] Warning: there is a version miss-match between the server and the client! the client is running a newer version, Fire-IO may not work as expected or fail entirely. Please update your client to release " + serverVersion.getCoreVersion());

        }

        //create socket and connect!
        socketModule = new SocketModule(this, host, port, assignedId, connectionArguments, connectionMeta);
        return this;
    }


    /**
     * Send a packet over a channel to the server
     *
     * @param channel
     * @param packet
     * @return
     */
    public FireIoClient send(String channel, Packet packet) {
        //send a custom packet via a channel, this will trigger the appropriate channel on the server side with our custom packet
        try {
            //create an internal packet object containing our channel and custom packet
            //the sender value is null since the server fills it in with the object for this client for easy back and forwards communication via the event system
            socketModule.getConnection().emit(new ChannelPacketPacket(null, channel, packet));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return this;
    }


    /**
     * Send a message (string) over a channel to the server
     *
     * @param channel
     * @param message
     * @return
     */
    public FireIoClient send(String channel, String message) {
        //send a string via a channel, this will trigger the appropriate channel on the server side with our string
        try {
            //create an internal packet containing our channel and string and then send it via the connection manager
            socketModule.getConnection().emit(new ChannelMessagePacket(channel, message));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return this;
    }


    /**
     * Close the connection in a normal way
     *
     * @return
     */
    public FireIoClient close() {
        //prepare and then close the connection
        socketModule.getConnection().close();
        pool.shutdown();
        return this;
    }

    /**
     * Submit a request to the server, with a callback function for when the request is finished
     *
     * @param channel
     * @param request
     * @param callback
     * @return
     */
    public FireIoClient request(String channel, RequestBody request, ClientRequest callback) {
        //create a request!
        //kinda like completablefutures but cross server!
        //handle request in requestmodule
        clientRequestModule.createRequest(channel, request, callback);
        return this;
    }


    /**
     * Another way to close the client,
     * effectively the same as close();
     *
     * @return
     */
    public FireIoClient stop() {
        //another function for close
        return close();
    }


    /**
     * Register an event handler for a specific Event
     * Mostly used for Fire-IO native API
     *
     * Deprecated since the Introduction of the new event api.
     *
     * @param e
     * @param r
     * @return
     */
    @Deprecated
    public FireIoClient on(Event e, Consumer<Client> r) {
        eventHandler.registerEvent(e).onExecute((client, string) -> r.accept(client));
        return this;
    }


    /**
     * Register an event handler for a specific Event
     * Mostly used for Fire-IO native API (with string payload)
     *
     * Deprecated since the Introduction of the new event api.
     *
     * @param e
     * @param r
     * @return
     */
    @Deprecated
    public FireIoClient on(Event e, BiConsumer<Client, String> r) {
        eventHandler.registerEvent(e).onExecute(r);
        return this;
    }


    /**
     * New event API
     * This registers a handler for a packet class on a channel with a priority
     *
     * @param packet
     * @param channel
     * @param priority
     * @param <E>
     * @return
     */
    public <E extends Packet> EventExecutor<E> onPacket(Class<E> packet, String channel, EventPriority priority) {
        return eventHandler.registerEvent(packet, channel , priority);
    }


    /**
     * New event API
     * This registers a handler for a packet class on a channel with a normal priority
     *
     * @param packet
     * @param channel
     * @param <E>
     * @return
     */
    public <E extends Packet> EventExecutor<E> onPacket(Class<E> packet, String channel) {
        return eventHandler.registerEvent(packet, channel , EventPriority.NORMAL);
    }


    /**
     * Register an string handler for a specific Channel
     * Mostly used for Fire-IO native API (with string payload)
     *
     * Deprecated since the Introduction of the new event api.
     *
     * @param r
     * @return
     */
    @Deprecated
    public FireIoClient on(String string, BiConsumer<Client, String> r) {
        eventHandler.registerTextChannel(string, EventPriority.NORMAL).onExecute((client, message) -> r.accept(client, message));
        return this;
    }


    /**
     * get thread pool, for internal usage
     *
     * @return
     */
    @Override
    public ThreadPool getPool() {
        return pool;
    }
}
