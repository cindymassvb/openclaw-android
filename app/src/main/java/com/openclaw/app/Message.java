package com.openclaw.app;

public class Message {
    public static final String ROLE_USER      = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    private String  role;
    private String  content;
    private boolean streaming;

    public Message(String role, String content) {
        this.role      = role;
        this.content   = content;
        this.streaming = false;
    }

    public String  getRole()      { return role; }
    public String  getContent()   { return content; }
    public boolean isStreaming()  { return streaming; }
    public boolean isUser()       { return ROLE_USER.equals(role); }

    public void setContent(String c)    { this.content   = c; }
    public void setStreaming(boolean s) { this.streaming = s; }
}
