package com.cottonlesergal.whisperclient.models;

public class Friend {
    private final String username, pubKey, inboxUrl, displayName;
    public Friend(String username, String pubKey, String inboxUrl, String displayName){
        this.username=username; this.pubKey=pubKey; this.inboxUrl=inboxUrl; this.displayName=displayName;
    }
    public String getUsername(){ return username; }
    public String getPubKey(){ return pubKey; }
    public String getInboxUrl(){ return inboxUrl; }
    public String getDisplayName(){ return displayName; }
    @Override public String toString(){ return displayName + " (@" + username + ")"; }
}
