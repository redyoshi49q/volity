package org.volity.client;

import java.awt.Color;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.util.*;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smackx.packet.MUCUser;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.WrapFactory;
import org.volity.client.Audio;
import org.volity.client.comm.RPCDispatcherDebug;
import org.volity.client.data.Metadata;
import org.volity.client.data.VersionNumber;
import org.volity.client.data.VersionSpec;
import org.volity.client.translate.TranslateToken;
import org.volity.jabber.*;

/**
 * A game user interface.
 */
public abstract class GameUI implements RPCHandler {

    /**
     * This describes the API version implemented in this file.
     */
    public static VersionNumber sUIVersion;
    static {
        try {
            sUIVersion = new VersionNumber("3.2");
        }
        catch (VersionNumber.VersionFormatException ex) {
            throw new RuntimeException("Unable to create UI version number");
        }
    }

    /**
     * (The arguments here are slightly redundant -- we could implement the
     * FailureToken-grabbing part of errorHandler in this class using
     * translator and messageHandler. But then we'd have to catch more
     * exceptions. Maybe we should do it anyway.)
     *
     * @param baseURL the top-level SVG document
     * @param connection an authenticated connection to an XMPP server
     * @param translator a token translation instance
     * @param messageHandler a handler for UI script and RPC messages
     *        (will display messages in-line -- e.g., in a message pane)
     * @param errorHandler   a handler for UI script and RPC exceptions
     *        (will display problems out-of-band -- e.g., a dialog box)
     * @throws IllegalStateException if the connection has not been
     *         authenticated
     */
    public GameUI(URI ruleset, URL baseURL, 
        XMPPConnection connection, TranslateToken translator,
        MessageHandler messageHandler, ErrorHandler errorHandler) {

        this.ruleset = ruleset;
        this.baseURL = baseURL;
        this.translator = translator;
        this.errorHandler = errorHandler;
        this.messageHandler = messageHandler;

        this.metadata = loadMetadata();

        this.dispatcher = new RPCDispatcherDebug(messageHandler);
        dispatcher.setHandler("game", this);
        dispatcherVolity = new VolityHandler(this);
        dispatcher.setHandler("volity", dispatcherVolity);
        filter = new GamePacketFilter(this);
        responder = new RPCResponder(connection, filter, dispatcher);
        responder.start();
    }

    URI ruleset;
    URL baseURL;
    TranslateToken translator;
    ErrorHandler errorHandler;
    MessageHandler messageHandler;
    Metadata metadata;
    RPCResponder responder;
    RPCDispatcher dispatcher;
    VolityHandler dispatcherVolity;
    GamePacketFilter filter;
    Scriptable scope, game, volity, info;
    GameTable table;
    Map seatObjects = new HashMap();
    
    public interface ErrorHandler {
        /**
         * Report a command error.
         */
        public abstract void error(Exception e);
    }
    
    public interface MessageHandler {
        /**
         * Report a status or game-response message.
         */
        public abstract void print(String msg);
    }

    public interface Completion {
        public abstract void result(Object obj);
        public abstract void error(Exception ex);
    }

    /**
     * Abstract method. This should read metadata from the gamefile (baseURL).
     * If there is no metadata, create a blank Metadata() and return that.
     */
    public abstract Metadata loadMetadata();

    /**
     * Get the metadata that was loaded from the UI.
     */
    public Metadata getMetadata() {
        return metadata;
    }

    /**
     * Disconnect the RPC handler and cease work. Call this when the user
     * interface (e.g., the window) closes. You should also call it before
     * reloading or changing the UI, because that triggers the creation of a
     * new UI.
     */
    public void stop() {
        Audio.stopGroup(this);
        responder.stop();

        // Expunge our references to everything, so that garbage collection
        // works.

        dispatcher.clear();
        dispatcherVolity.stop();
        dispatcherVolity = null;

        filter.stop();
        filter = null;

        seatObjects.clear();

        if (scope != null) {
            scope.delete("game");
            scope.delete("volity");
            scope.delete("info");
            scope.delete("metadata");
            scope.delete("rpc");
            scope.delete("literalmessage");
            scope.delete("localize");
            scope.delete("message");
            scope.delete("seatmark");
            scope.delete("audio");
            scope = null;
        }
        game = null;
        volity = null;
        info = null;
    }

    /**
     * Initialize game-handling objects. Call this inside a ContextAction
     * when setting up the Interpreter.
     *
     * @param context a context to run in.
     * @param scope the scope to initialize. May not be null.
     */
    public void initGameObjects(Context context, ScriptableObject scope) {
        try {
            this.scope = scope;
            scope.put("game", scope, game = context.newObject(scope));
            scope.put("volity", scope, volity = context.newObject(scope));
            scope.put("info", scope, info = new Info());
            scope.put("metadata", scope, new MetadataObj());
            scope.put("rpc", scope, new Callback() {
                    public Object run(Object[] args) {
                        try {
                            String methname = args[0].toString();
                            if (methname.indexOf('.') < 0)
                                methname = "game."+methname;
                            List params = Arrays.asList(args).subList(1, args.length);
                            params = unwrapRPCTypes(params);
                            return table.getReferee().invoke(methname, params);
                        } catch (Exception e) {
                            errorHandler.error(e);
                            /* This will print TokenFailures in window log, and
                             * display other exceptions as dialog box */
                            return null;
                        }
                    }
                });
            scope.put("literalmessage", scope, new Callback() {
                    public Object run(Object[] args) {
                        try {
                            if (args.length != 1) {
                                throw new Exception("literalmessage() requires one argument");
                            }
                            if (args[0] == null)
                                messageHandler.print("null");
                            else
                                messageHandler.print(args[0].toString());
                            return null;
                        } catch (Exception e) {
                            errorHandler.error(e);
                            return null;
                        }
                    }
                });
            scope.put("localize", scope, new Callback() {
                    public Object run(Object[] args) {
                        try {
                            if (args.length == 0)
                                throw new Exception("localize() requires at least one argument");
                            List params = massageTokenList(args);
                            return translator.translate(params);
                        } catch (Exception e) {
                            errorHandler.error(e);
                            return null;
                        }
                    }
                });
            scope.put("message", scope, new Callback() {
                    public Object run(Object[] args) {
                        try {
                            if (args.length == 0)
                                throw new Exception("message() requires at least one argument");
                            List params = massageTokenList(args);
                            messageHandler.print(translator.translate(params));
                            return null;
                        } catch (Exception e) {
                            errorHandler.error(e);
                            return null;
                        }
                    }
                });
            scope.put("seatmark", scope, new Callback() {
                    public Object run(Object[] args) {
                        try {
                            Map map = parseSeatMarkArgs(args);
                            table.setSeatMarks(map);
                            return null;
                        } catch (Exception ex) {
                            errorHandler.error(ex);
                            return null;
                        }
                    }
                });
            scope.put("audio", scope, 
                UIAudio.makeCallableProperty(this, baseURL, 
                    messageHandler, errorHandler));

            /* If you add more identifiers to scope, be sure to delete them in
             * the stop() method. */

        } catch (Exception ex) {
            errorHandler.error(ex);
        }
    }

    /**
     * A couple of Javascript-accessible functions (message and localize) take
     * translation tokens as arguments. We need to ensure that these lists
     * contains only strings.
     *
     * Also, as a convenience, we convert Java numeric objects to literal
     * strings. That way, message("game.your_score_was", 6) will work nicely.
     *
     * @param args a list of anything (preferably tokens strings)
     */
    protected List massageTokenList(Object[] args) {
        List ls = new ArrayList();
        for (int ix=0; ix<args.length; ix++) {
            Object obj = args[ix];
            if (obj instanceof String) {
                // ok as is
            }
            else if (obj instanceof Number) {
                obj = "literal." + String.valueOf(obj);
            }
            else if (obj == null) {
                obj = "literal.(null)";
            }
            else {
                obj = "literal.(unprintable object " + obj.toString() + ")";
            }
            ls.add(obj);
        }
        return ls;
    }

    /**
     * Parse the various allowable arguments to a seatmeak() call. Return a Map
     * (mapping String seatids to String mark names). Some keys may be invalid
     * seat IDs, and some values may be invalid mark names, but neither will be
     * null.
     */
    public static Map parseSeatMarkArgs(Object[] args) {
        Map map = new HashMap();

        if (args.length == 0)
            return map;

        if (args.length > 1)
            throw new IllegalArgumentException("seatmark must have zero or one arguments");

        Object arg = args[0];

        if (arg != null && arg instanceof NativeArray) {
            NativeArray arr = (NativeArray)arg;
            Object[] ids = arr.getIds();
            for (int ix=0; ix<ids.length; ix++) {
                Object id = ids[ix];
                Object val;
                if (id instanceof Integer)
                    val = arr.get(((Integer)id).intValue(), arr);
                else
                    val = arr.get(id.toString(), arr);

                if (id == null || (id instanceof Undefined))
                    throw new IllegalArgumentException("seatmark map keys may not be null.");
                if (id instanceof ScriptableObject) {
                    id = ((ScriptableObject)id).getDefaultValue(String.class);
                }
                if (!(id instanceof String)) 
                    throw new IllegalArgumentException("seatmark map keys must be convertible to strings");

                if (val == null || (val instanceof Undefined))
                    continue;
                if (!(val instanceof String))
                    throw new IllegalArgumentException("seatmark map values must be strings or null");
                if (val.equals(""))
                    continue;

                map.put(id, val);
            }

            return map;
        }

        if (arg != null && arg instanceof String) {
            map.put(arg, "turn");
            return map;
        }
        if (arg != null && arg instanceof ScriptableObject) {
            Object val = ((ScriptableObject)arg).getDefaultValue(String.class);
            if (val instanceof String) {
                map.put((String)val, "turn");
                return map;
            }
        }
        throw new IllegalArgumentException("seatmark must take no argument, a string, or an associative array.");
    }

    /**
     * Expand a namespaced metadata key (UI style, like "dc.title" or
     * "volity.version") into a Metadata key.
     */
    public static String expandKey(String val) {
        String namespace;
        int pos = val.indexOf('.');
        if (pos >= 0) {
            namespace = val.substring(0, pos);
            val = val.substring(pos+1);
        }
        else {
            namespace = "game";
        }

        String uri = "NONEXISTENT";
        if (namespace.equals("dc"))
            uri = Metadata.NS_DC;
        else if (namespace.equals("game"))
            uri = Metadata.NS_GAME;
        else if (namespace.equals("volity"))
            uri = Metadata.NS_VOLITY;
        return Metadata.createKey(uri, val);
    }

    /**
     * Expand a hex color string ("#HHH" or "#HHHHHH") into a Color object.
     * If the string doesn't match one of these formats, returns null.
     */
    public static Color parseColor(String val) {
        if (val == null)
            return null;
        if (!val.startsWith("#"))
            return null;

        if (val.length() != 4 && val.length() != 7) 
            return null;

        int rval, bval, gval;

        try {
            if (val.length() == 4) {
                rval = Integer.parseInt(val.substring(1,2), 16);
                gval = Integer.parseInt(val.substring(2,3), 16);
                bval = Integer.parseInt(val.substring(3,4), 16);
                rval *= 0x11;
                gval *= 0x11;
                bval *= 0x11;
            }
            else {
                rval = Integer.parseInt(val.substring(1,3), 16);
                gval = Integer.parseInt(val.substring(3,5), 16);
                bval = Integer.parseInt(val.substring(5,7), 16);
            }
        }
        catch (NumberFormatException ex) {
            return null;
        }

        if (rval < 0 || gval < 0 || bval < 0)
            return null;
        if (rval >= 256 || gval >= 256 || bval >= 256)
            return null;

        return new Color(rval, gval, bval);
    }

    /**
     * Return the UISeat object for a given Seat.
     *
     * We keep a cache of these, in a hash table. Any call to getUISeat() for a
     * given Seat returns the same UISeat. (This is true even if the seating
     * system discards a Seat and replaces it with a new one that has the same
     * ID. Not that this should happen... but if it does, it should be
     * transparent to the UI.)
     *
     * Why? If there were two UISeat objects for the same ID, they'd compare as
     * "not equal" in UI code -- even though they'd appear identical. This
     * would be confusing and weird.
     */
    UISeat getUISeat(Seat gameseat) {
        String id = gameseat.getID();
        if (!seatObjects.containsKey(id)) {
            UISeat seat = new UISeat(id);
            seatObjects.put(id, seat);
            return seat;
        }
        else {
            UISeat seat = (UISeat)seatObjects.get(id);
            return seat;
        }
    }

    public class Info extends ScriptableObject {
        private Callable funcVersionMatch;

        {
            defineProperty("version", Info.class, PERMANENT);
            defineProperty("state", Info.class, PERMANENT);
            defineProperty("recovery", Info.class, PERMANENT);
            defineProperty("nickname", Info.class, PERMANENT);
            defineProperty("seat", Info.class, PERMANENT);
            defineProperty("allseats", Info.class, PERMANENT);
            defineProperty("gameseats", Info.class, PERMANENT);
            defineProperty("ruleset", Info.class, PERMANENT);
            defineProperty("versionmatch", Info.class, PERMANENT);

            funcVersionMatch = new Callback() {
                    public Object run(Object[] args) {
                        if (args.length != 2)
                            throw new RuntimeException("versionmatch() requires two arguments");
                        VersionNumber vnum;
                        VersionSpec vspec;
                        try {
                            vnum = new VersionNumber(args[0].toString());
                        }
                        catch (Exception ex) {
                            throw new RuntimeException(ex.getMessage());
                        }
                        try {
                            vspec = new VersionSpec(args[1].toString());
                        }
                        catch (Exception ex) {
                            throw new RuntimeException(ex.getMessage());
                        }
                        boolean result = vspec.matches(vnum);
                        return new Boolean(result);
                    }
                };
        }

        public String getClassName() { return "Info"; }
        public Object getDefaultValue(Class typeHint) { return toString(); }

        public String getVersion() {
            return sUIVersion.toString();
        }
        public String getState() {
            int val = table.getRefereeState();
            return table.refereeStateToString(val);
        }
        public Boolean getRecovery() {
            boolean val = table.getInStateRecovery();
            return Boolean.valueOf(val);
        }
        public UISeat getSeat() { 
            Player player = table.getSelfPlayer();
            if (player == null)
                return null;
            Seat seat = player.getSeat();
            if (seat == null)
                return null;
            return getUISeat(seat);
        }
        public String getNickname() { return table.getNickname(); }
        public void setNickname(String nickname) throws XMPPException {
            table.changeNickname(nickname);
        }
        public Object getAllseats() {
            Context context = Context.getCurrentContext();
            Scriptable ls = context.newArray(scope, 0);
            int count = 0;
            for (Iterator it = table.getSeats(); it.hasNext(); ) {
                Seat seat = (Seat)it.next();
                ls.put(count++, ls, getUISeat(seat));
            }
            return ls;
        }
        public Object getGameseats() {
            Context context = Context.getCurrentContext();
            Scriptable ls = context.newArray(scope, 0);
            int count = 0;
            for (Iterator it = table.getVisibleSeats(); it.hasNext(); ) {
                Seat seat = (Seat)it.next();
                ls.put(count++, ls, getUISeat(seat));
            }
            return ls;
        }
        public String getRuleset() {
            return ruleset.toString();
        }
        public Callable getVersionmatch() {
            return funcVersionMatch;
        }
    }

    class MetadataObj extends ScriptableObject {
        private Callable funcGet;
        private Callable funcGetall;

        {
            defineProperty("get", MetadataObj.class, PERMANENT);
            defineProperty("getall", MetadataObj.class, PERMANENT);

            funcGet = new Callback() {
                    public Object run(Object[] args) {
                        if (args.length != 2 && args.length != 3)
                            throw new RuntimeException("get() requires two or three arguments");
                        Object uristr = args[0];
                        String label = args[1].toString();
                        Object defaultval = null;
                        if (args.length == 3)
                            defaultval = args[2];

                        Metadata data;
                        URI uri = null;
                        if (!(uristr == null || uristr instanceof Undefined)) {
                            try {
                                uri = new URI(uristr.toString());
                            }
                            catch (Exception ex) { }
                        }
                        if (uri == null)
                            data = metadata;
                        else
                            data = metadata.getResource(uri);
                        if (data == null)
                            return null;
                        Object res = data.get(expandKey(label), TranslateToken.getLanguage());
                        if (res == null)
                            res = defaultval;
                        return res;
                    }
                };

            funcGetall = new Callback() {
                    public Object run(Object[] args) {
                        if (args.length != 2)
                            throw new RuntimeException("getall() requires two arguments");
                        Object uristr = args[0];
                        String label = args[1].toString();

                        Metadata data;
                        URI uri = null;
                        if (!(uristr == null || uristr instanceof Undefined)) {
                            try {
                                uri = new URI(uristr.toString());
                            }
                            catch (Exception ex) { }
                        }
                        if (uri == null)
                            data = metadata;
                        else
                            data = metadata.getResource(uri);
                        List res = data.getAll(expandKey(label));

                        Context context = Context.getCurrentContext();
                        Scriptable ls = context.newArray(scope, 0);
                        for (int ix=0; ix<res.size(); ix++) {
                            ls.put(ix, ls, res.get(ix));
                        }
                        return ls;
                    }
                };
        }

        public String getClassName() { return "Metadata"; }
        public Object getDefaultValue(Class typeHint) { return toString(); }

        public Callable getGet() {
            return funcGet;
        }
        public Callable getGetall() {
            return funcGetall;
        }
    }

    class UISeat extends ScriptableObject {
        {
            defineProperty("players", UISeat.class, PERMANENT);
            defineProperty("nicknames", UISeat.class, PERMANENT);
        }

        protected String id;

        public UISeat(String id) {
            this.id = id;
        }

        public String getClassName() { return "Seat"; }
        public Object getDefaultValue(Class typeHint) { return id; }

        public Object getPlayers() {
            Context context = Context.getCurrentContext();
            Scriptable ls = context.newArray(scope, 0);
            Seat seat = table.getSeat(id);
            if (seat != null) {
                int count = 0;
                for (Iterator it = seat.getPlayers(); it.hasNext(); ) {
                    Player player = (Player)it.next();
                    ls.put(count++, ls, player.getJID());
                }
            }
            return ls;
        }

        public Object getNicknames() {
            Context context = Context.getCurrentContext();
            Scriptable ls = context.newArray(scope, 0);
            Seat seat = table.getSeat(id);
            if (seat != null) {
                int count = 0;
                for (Iterator it = seat.getPlayers(); it.hasNext(); ) {
                    Player player = (Player)it.next();
                    ls.put(count++, ls, player.getNick());
                }
            }
            return ls;
        }
    }

    /**
     * UIAudio is an ECMA wrapper for Audio objects. It provides the properties
     * that the UI code uses to manipulate the audio.
     *
     * (This is used by Testbench as well as Javolin. That's why it's a static
     * class.)
     */
    public static class UIAudio extends ScriptableObject {
        {
            defineProperty("url", UIAudio.class, PERMANENT);
            defineProperty("loop", UIAudio.class, PERMANENT);
            defineProperty("alt", UIAudio.class, PERMANENT);
            defineProperty("play", UIAudio.class, PERMANENT);
        }

        /**
         * Create a script object which will serve as the factory for audio
         * objects.
         *
         * The object returned by makeCallableProperty should be inserted into
         * the ECMAScript scope, under the name "audio". When the UI calls this
         * object (with one or two arguments), an audio object will be created
         * and returned.
         *
         * @param owner the GameUI (or TestUI) which will own these audio
         * objects. (We need to know the owner in order to kill all sounds when
         * closing a window.)
         * @param baseURL the URL of the UI document. (We need to know this in
         * order to parse relative URLs for sound resources.)
         * @param errorHandler a service to drop errors into.
         */
        public static ScriptableObject makeCallableProperty(final Object owner,
            final URL baseURL, 
            final MessageHandler messageHandler,
            final ErrorHandler errorHandler) {
            return new Callback() {
                    public Object run(Object[] args) {
                        try {
                            if (args.length < 1)
                                throw new Exception("audio() requires at least one argument");
                            URL url = new URL(baseURL, args[0].toString());

                            // Get the alt tag, if provided
                            String alt = "";
                            if (args.length >= 2) {
                                Object altval = args[1];
                                if (altval != null && !(altval instanceof Undefined))
                                    alt = altval.toString();
                            }

                            Audio audio = new Audio(owner, url, alt, messageHandler);
                            return new UIAudio(audio, errorHandler);
                        } catch (Exception ex) {
                            errorHandler.error(ex);
                            return null;
                        }
                    }
                };  
        }

        protected Audio audio;
        protected ErrorHandler errorHandler;

        /**
         * The constructor. Do not call this directly; go through
         * makeCallableProperty.
         */
        protected UIAudio(Audio audio, ErrorHandler errorHandler) {
            this.audio = audio;
            this.errorHandler = errorHandler;
        }

        public String getClassName() { return "Audio"; }
        public Object getDefaultValue(Class typeHint) { 
            return audio.getURL().toString();
        }

        public Object getUrl() {
            return audio.getURL().toString();
        }
        public void setUrl(Object val) {
            throw new RuntimeException("audio.url is immutable");
        }

        public Object getAlt() {
            return audio.getAlt();
        }
        public void setAlt(Object val) {
            if (val == null || val instanceof Undefined)
                val = "";
            audio.setAlt(val.toString());
        }

        public Object getLoop() {
            int loop = audio.getLoop();
            if (loop == Audio.LOOP_CONTINUOUSLY)
                return Boolean.TRUE;
            if (loop == 1)
                return Boolean.FALSE;
            return new Integer(loop);
        }
        public void setLoop(Object val) {
            int loop;

            if (val == null || val instanceof Undefined)
                loop = 1;
            else if (val == Boolean.TRUE)
                loop = Audio.LOOP_CONTINUOUSLY;
            else if (val == Boolean.FALSE)
                loop = 1;
            else if (val instanceof Integer)
                loop = ((Integer)val).intValue();
            else {
                loop = (int)Double.parseDouble(val.toString());
            }

            audio.setLoop(loop);
        }

        public Object getPlay() {
            return new Callback() {
                    public Object run(Object[] args) {
                        try {
                            Audio.Instance ain = audio.play();
                            return new UIAudioInstance(ain);
                        } catch (Exception ex) {
                            errorHandler.error(ex);
                            return null;
                        }
                    }
                };
        }
    }

    /**
     * UIAudioInstance is an ECMA wrapper for Audio.Instance objects.
     */
    static class UIAudioInstance extends ScriptableObject {
        {
            defineProperty("stop", UIAudioInstance.class, PERMANENT);
        }

        protected Audio.Instance instance;

        public UIAudioInstance(Audio.Instance instance) {
            this.instance = instance;
        }

        public String getClassName() { return "AudioInstance"; }
        public Object getDefaultValue(Class typeHint) { 
            return toString();
        }

        public Object getStop() {
            return new Callback() {
                    public Object run(Object[] args) {
                        instance.stop();
                        return null;
                    }
                };
        }
    }

    /**
     * @param table the table where the game will be played
     */
    public void setTable(GameTable table) {
        this.table = table;
    }
  
    protected static class GamePacketFilter implements PacketFilter {

        protected GameUI ui;

        public GamePacketFilter(GameUI ui) {
            this.ui = ui;
        }

        /* Shut down this filter, so it doesn't refer to the dead GameUI and it
         * doesn't accept packets. */
        public void stop() {
            this.ui = null;
        }

        // Implements PacketFilter interface.
        public boolean accept(Packet packet) {
            /* Only accept packets from the referee at this table, because the
             * user might be playing at multiple tables (perhaps even in
             * multiple instances of the application).
             */

            if (ui == null || ui.table == null)
                return false;

            Referee ref = ui.table.getReferee();
            // If the referee is missing, we should usually assume that this
            // packet is a wayward request and ignore it. But because filters
            // run in a different thread from listeners (!) the referee's
            // presence packet might still be waiting to be processed, so we
            // are lenient here and accept this packet, with a second check in
            // the actual listener (see RPCResponder.processPacket). A future
            // version of Smack may fix this, in which case the check should be
            // made more restrictive again.
            /* return ref != null && */
            return ref == null ||
                ref.getResponderJID().equals(packet.getFrom());
        }
    }

    // Implements RPCHandler interface.
    public void handleRPC(String methodName, List params, 
        final RPCResponseHandler k) {
        Object method = game.get(methodName, scope);

        if (!(method instanceof Function)) {
            k.respondFault(603, "No game."+methodName+" function in UI.");
            return;        
        }

        /*
         * Call the method, passing a callback which will be certain to respond
         * to the RPC.
         */

        callUIMethod((Function) method, params, new Completion() {
                public void result(Object obj) {
                    if (obj == null || obj instanceof Undefined) {
                        // function returned null/void, but RPC result has to
                        // be non-void
                        obj = Boolean.TRUE;
                    }
                    k.respondValue(obj);
                }
                public void error(Exception ex) {
                    errorHandler.error(ex);
                    k.respondFault(608, "UI script error: " + ex.toString());
                }
            });
    }

    /**
     * Execute an ECMAScript function object. This is an abstract method, which
     * should be overridden by the SVGCanvas class.
     *
     * Since this is a slow operation, probably involving work in another
     * thread, it does not return a value or throw exceptions. Instead, you may
     * supply a callback which will be called when the method completes. (The
     * callback may not be called in your thread!) If the callback is null, the
     * result (or exception) of the method is silently dropped.
     *
     * A word on concurrency: it is a bad idea for the UI to execute two
     * methods in different threads at the same time. ECMAScript isn't built
     * for multithreading, and if it were, UI authors still wouldn't want to do
     * it. However, it is not useful to put serialization guards (say, mutexes)
     * in this method. The right way to solve the problem is to queue
     * everything in one thread -- but the UI package (Batik) will have its own
     * methods for doing this.
     *
     * Therefore, you must subclass GameUI, and wrap callUIMethod in code that
     * does the appropriate serialization. (For Batik, that means calling
     * getUpdateRunnableQueue.invokeLater(). See SVGUI in SVGCanvas.)
     *
     * @param method the UI method to be called
     * @param params the list of method arguments (RPC data objects)
     * @param callback completion function, or null
     */
    public abstract void callUIMethod(Function method, List params, 
        Completion callback);

    /**
     * Create a ContextAction which does the work of calling a method,
     * translating the result, and invoking the callback. This is a utility
     * method which should be used by the callUIMethod() implementation.
     */
    public ContextAction uiMethodAction(final ScriptableObject global,
        final Function method, final List params, final Completion callback) {

        return new ContextAction() {
                public Object run(Context context) {
                    /* If we haven't already, put an RPCWrapFactory around
                     * whatever WrapFactory exists on the context. (We don't
                     * want to give up the BatikWrapFactory which exists on
                     * Batik contexts.) */
                    WrapFactory wrapper = context.getWrapFactory();
                    if (!(wrapper instanceof GameUI.RPCWrapFactory)) {
                        wrapper = new GameUI.RPCWrapFactory(wrapper);
                        context.setWrapFactory(wrapper);
                    }

                    Object ret = method.call(context,
                        global, global, params.toArray());
                    if (callback != null) 
                        callback.result(ret);
                                    
                    return null;
                }
            };    
    }

    /**
     * A factory for wrapping RPC data types into JavaScript objects.
     * In particular, RPC arrays and structs are List objects and Map
     * objects, respectively, and this turns them both into Scriptables.
     *
     * This class is intended to wrap (in a different sense!) around an
     * existing WrapFactory. This lets us do data wrapping on Lists and Maps,
     * without breaking the BatikWrapFactory that's already in place on Batik
     * contexts.
     */
    static public class RPCWrapFactory extends WrapFactory {
        WrapFactory innerWrapper;

        public RPCWrapFactory(WrapFactory innerWrapper) {
            super();
            if (innerWrapper == null)
                throw new NullPointerException("RPCWrapFactory must wrap a non-null factory!");
            this.innerWrapper = innerWrapper;
        }

        public Object wrap(Context cs, Scriptable scope,
            Object obj, Class staticType)
        {
            if (obj != null && obj instanceof List) {
                final List list = (List) obj;
                return new Scriptable() {
                        public boolean has(int index, Scriptable start) {
                            return index >= 0 && index < list.size();
                        }
                        public Object get(int index, Scriptable start) {
                            return list.get(index);
                        }
                        public void put(int index, Scriptable start, Object value) {
                            list.set(index, value);
                        }
                        public void delete(int index) {
                            list.remove(index);
                        }
                        public Object[] getIds() {
                            Object[] ids = new Object[list.size()];
                            for (int i = 0; i < ids.length; i++)
                                ids[i] = new Integer(i);
                            return ids;
                        }

                        // Not really sure what to do with all these...
                        public String getClassName() {
                            return list.getClass().getName();
                        }
                        public Object getDefaultValue(Class hint) {
                            if (hint == null || hint == String.class)
                                return list.toString();
                            if (hint == Boolean.class)
                                return Boolean.TRUE;
                            if (hint == Number.class)
                                return new Double(Double.NaN);
                            return this;
                        }
                        public boolean hasInstance(Scriptable instance) {
                            return false;
                        }
                        public boolean has(String name, Scriptable start) {
                            if (name.equals("length"))
                                return true;
                            return false;
                        }
                        public Object get(String name, Scriptable start) {
                            if (name.equals("length"))
                                return new Integer(list.size());
                            return NOT_FOUND;
                        }
                        public void put(String name, Scriptable start, Object value) {
                            // ignore
                        }
                        public void delete(String name) {
                            // ignore
                        }
                        public Scriptable getParentScope() {
                            return null;
                        }
                        public void setParentScope(Scriptable parent) {
                            // ignore
                        }
                        public Scriptable getPrototype() {
                            return null;
                        }
                        public void setPrototype(Scriptable prototype) {
                            // ignore;
                        }
                    };
            } else if (obj != null && obj instanceof Map) {
                final Map map = (Map) obj;
                return new Scriptable() {
                        public boolean has(String name, Scriptable start) {
                            return map.containsKey(name);
                        }
                        public Object get(String name, Scriptable start) {
                            return map.get(name);
                        }
                        public void put(String name, Scriptable start, Object value) {
                            map.put(name, value);
                        }
                        public void delete(String name) {
                            map.remove(name);
                        }

                        public Object[] getIds() {
                            return map.keySet().toArray();
                        }

                        // Not really sure what to do with all these...
                        public String getClassName() {
                            return map.getClass().getName();
                        }
                        public Object getDefaultValue(Class hint) {
                            if (hint == null || hint == String.class)
                                return map.toString();
                            if (hint == Boolean.class)
                                return Boolean.TRUE;
                            if (hint == Number.class)
                                return new Double(Double.NaN);
                            return this;
                        }
                        public boolean hasInstance(Scriptable instance) {
                            return false;
                        }
                        public boolean has(int index, Scriptable start) {
                            return false;
                        }
                        public Object get(int index, Scriptable start) {
                            return NOT_FOUND;
                        }
                        public void put(int index, Scriptable start, Object value) {
                            // ignore
                        }
                        public void delete(int index) {
                            // ignore
                        }
                        public Scriptable getParentScope() {
                            return null;
                        }
                        public void setParentScope(Scriptable parent) {
                            // ignore
                        }
                        public Scriptable getPrototype() {
                            return null;
                        }
                        public void setPrototype(Scriptable prototype) {
                            // ignore;
                        }
                    };
            } else {
                return innerWrapper.wrap(cs, scope, obj, staticType);
            }
        }
    }

    public static List unwrapRPCTypes(List params) 
        throws BadRPCTypeException {
        // Might as well special-case this
        if (params.size() == 0)
            return params;

        List result = new ArrayList();

        for (int ix=0; ix<params.size(); ix++) {
            Object val = params.get(ix);
            result.add(unwrapRPCValue(val));
        }

        return result;
    }

    public static Object unwrapRPCValue(Object obj)
    throws BadRPCTypeException {
        if (obj instanceof String)
            return obj;
        if (obj instanceof Boolean)
            return obj;
        if (obj instanceof Number)
            return obj;
        
        if (obj instanceof NativeArray) {
            NativeArray arr = (NativeArray)obj;
            Object[] ids = arr.getIds();

            boolean simple = true;
            for (int ix=0; ix<ids.length; ix++) {
                Object id = ids[ix];
                if (id instanceof Number && ((Number)id).doubleValue() == ix)
                    continue;
                simple = false;
                break;
            }

            if (simple) {
                List result = new ArrayList();
                for (int ix=0; ix<ids.length; ix++) {
                    Object id = ids[ix];
                    Object val = arr.get(ix, arr);
                    result.add(unwrapRPCValue(val));
                }
                return result;
            }
            else {
                Map result = new HashMap();
                for (int ix=0; ix<ids.length; ix++) {
                    Object id = ids[ix];
                    Object val;
                    if (id instanceof Integer)
                        val = arr.get(((Integer)id).intValue(), arr);
                    else
                        val = arr.get(id.toString(), arr);
                    if (val == null || val instanceof Undefined)
                        continue;
                    result.put(id, val);
                }
                return result;
            }
        }

        throw new BadRPCTypeException("cannot send object " + obj);
    }

    public static class BadRPCTypeException extends Exception {
        public BadRPCTypeException(String val) {
            super(val);
        }
    }

    /**
     * A simple way to define a function object without using reflection.
     */
    public static abstract class Callback extends ScriptableObject implements Function {
        // Inherited from ScriptableObject.
        public String getClassName() { return "Function"; }
        public Object getDefaultValue(Class typeHint) { return "Function"; }
        // Inherited from Function.
        public Object call(Context cx, Scriptable scope, Scriptable thisObj,
            Object[] args) {
            return run(args);
        }
        // Inherited from Function.
        public Scriptable construct(Context cx, Scriptable scope,
            Object[] args) {
            throw new RuntimeException("Not a constructor.");
        }
        /** Run the callback. */
        public abstract Object run(Object[] args);
    }
}
