package com.botwithus.bot.cli;

import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.ScriptManagerImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcRemoteException;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A stand-in for one agent's RPC surface: a canned reply per method, the agent's
 * "Unknown method" error for anything else, and a log of what was called. The
 * connection it builds is a real {@link Connection} with a real event bus and
 * script runtime; only the pipe and the RPC transport are stubbed.
 *
 * <p>Reply builders mirror the agent's reply shapes: {@link #olderAccountInfo}
 * is what an agent sends without the {@code account_name} / {@code game_state}
 * keys, {@link #accountInfo} what a newer one sends.</p>
 */
public final class FakeAgent {

    public static final String GET_ACCOUNT_INFO = "get_account_info";
    public static final String GET_LOGIN_STATE = "get_login_state";
    public static final String GET_CURRENT_WORLD = "get_current_world";
    public static final String LOGIN_TO_LOBBY = "login_to_lobby";

    private final Map<String, Map<String, Object>> replies = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final RpcClient rpc = mock(RpcClient.class);

    public FakeAgent() {
        when(rpc.callSync(anyString(), anyMap())).thenAnswer(call -> answer(call.getArgument(0)));
    }

    /** Answers {@code method} with {@code result} from now on. */
    public FakeAgent reply(String method, Map<String, Object> result) {
        replies.put(method, result);
        return this;
    }

    /** Answers {@code get_current_world} with {@code world}. */
    public FakeAgent world(int world) {
        return reply(GET_CURRENT_WORLD, Map.of("world_id", world));
    }

    /** Answers {@code get_login_state} with the raw state {@code code}. */
    public FakeAgent loginState(int code) {
        return reply(GET_LOGIN_STATE, Map.of("state", code, "login_progress", 0, "login_status", 0));
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    public long callsTo(String method) {
        return calls.stream().filter(method::equals).count();
    }

    public void forgetCalls() {
        calls.clear();
    }

    /** A live connection named {@code name} whose RPC is this agent. */
    public Connection connection(String name) {
        return connection(name, Instant.now());
    }

    public Connection connection(String name, Instant connectedAt) {
        PipeClient pipe = mock(PipeClient.class);
        when(pipe.isOpen()).thenReturn(true);
        ScriptRuntime runtime = new ScriptRuntime(mock(ScriptContext.class));
        Connection conn = new Connection(name, pipe, rpc, runtime, new ScriptManagerImpl(runtime), connectedAt);
        conn.setEventBus(new EventBusImpl());
        return conn;
    }

    /** A {@code get_account_info} reply from an agent that predates {@code account_name} and {@code game_state}. */
    public static Map<String, Object> olderAccountInfo(String displayName, String uuid, boolean loggedIn,
                                                       boolean isMember) {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("display_name", displayName);
        reply.put("jx_display_name", "");
        reply.put("logged_in", loggedIn);
        reply.put("is_member", isMember);
        reply.put("account_uuid", uuid);
        return reply;
    }

    /** A {@code get_account_info} reply from an agent that sends {@code account_name} and {@code game_state}. */
    public static Map<String, Object> accountInfo(String displayName, String accountName, String uuid,
                                                  int gameState, boolean isMember) {
        Map<String, Object> reply = olderAccountInfo(displayName, uuid, gameState == GameState.IN_GAME.wireCode(),
                isMember);
        reply.put("account_name", accountName);
        reply.put("game_state", gameState);
        return reply;
    }

    private Map<String, Object> answer(String method) {
        calls.add(method);
        Map<String, Object> reply = replies.get(method);
        if (reply == null) {
            throw new RpcRemoteException(method, "Unknown method: " + method);
        }
        return reply;
    }
}
