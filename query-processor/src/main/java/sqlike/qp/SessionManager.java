package sqlike.qp;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import sqlike.qp.port.QpContracts.SessionId;
import sqlike.shared.ErrorCode;
import sqlike.shared.Session;

/**
 * Registry of active client sessions matching CLASS DIAGRAM FIX (Tab 4).
 */
public final class SessionManager {
    private final ConcurrentMap<String, SessionState> sessions;

    public SessionManager() {
        this.sessions = new ConcurrentHashMap<>();
    }

    public Session openSession() {
        SessionState session = new SessionState(UUID.randomUUID().toString());
        sessions.put(session.getId(), session);
        return session.toSession();
    }

    public SessionId openSessionId() {
        UUID uuid = UUID.randomUUID();
        SessionId sid = new SessionId(uuid);
        SessionState session = new SessionState(sid);
        sessions.put(session.getId(), session);
        return sid;
    }

    public SessionState requireSession(String id) {
        SessionState session = find(id);
        SessionStatus status = session.getStatus();
        if (status == SessionStatus.CLOSING || status == SessionStatus.CLOSED) {
            throw new QpException(ErrorCode.INVALID_STATE, "Session " + id + " is " + status);
        }
        return session;
    }

    public SessionState requireSession(SessionId id) {
        return requireSession(id == null || id.value() == null ? null : id.value().toString());
    }

    public SessionState markClosing(String id) {
        SessionState session = find(id);
        session.markClosing();
        return session;
    }

    public SessionState markClosing(SessionId id) {
        return markClosing(id == null || id.value() == null ? null : id.value().toString());
    }

    public void removeClosed(String id) {
        SessionState session = id == null ? null : sessions.remove(id);
        if (session != null) {
            session.markClosed();
        }
    }

    public void removeClosed(SessionId id) {
        removeClosed(id == null || id.value() == null ? null : id.value().toString());
    }

    public List<SessionState> activeSessions() {
        return List.copyOf(sessions.values());
    }

    private SessionState find(String id) {
        SessionState session = id == null ? null : sessions.get(id);
        if (session == null) {
            throw new QpException(ErrorCode.INVALID_STATE, "Unknown session: " + id);
        }
        return session;
    }
}
