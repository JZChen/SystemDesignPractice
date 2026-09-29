package com.googledocs.model;

import java.time.Instant;
import java.util.Collection;

public class DocumentResponse {
    private String docId;
    private String url;
    private String sessionId;
    private String title;
    private EngineType engineType;
    private String content;
    private long revision;
    private Session user;
    private Collection<Session> activeUsers;
    private Instant createdAt;
    private Instant updatedAt;

    public DocumentResponse() {}

    public static DocumentResponse from(Document doc, String sessionId, String baseUrl) {
        DocumentResponse resp = new DocumentResponse();
        resp.docId = doc.getId();
        resp.url = "/docs/" + doc.getId();
        resp.sessionId = sessionId;
        resp.title = doc.getTitle();
        resp.engineType = doc.getEngineType();
        resp.content = doc.getContent();
        resp.revision = doc.getRevision();
        resp.user = sessionId != null ? doc.getSession(sessionId) : null;
        resp.activeUsers = doc.getSessions().values();
        resp.createdAt = doc.getCreatedAt();
        resp.updatedAt = doc.getUpdatedAt();
        return resp;
    }

    public String getDocId() {
        return docId;
    }

    public String getUrl() {
        return url;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTitle() {
        return title;
    }

    public EngineType getEngineType() {
        return engineType;
    }

    public String getContent() {
        return content;
    }

    public long getRevision() {
        return revision;
    }

    public Session getUser() {
        return user;
    }

    public Collection<Session> getActiveUsers() {
        return activeUsers;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
