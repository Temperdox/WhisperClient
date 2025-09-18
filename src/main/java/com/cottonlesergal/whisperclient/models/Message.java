package com.cottonlesergal.whisperclient.models;

public class Message {
    public enum Kind { TEXT, IMAGE }
    private Kind kind; private String text; private String base64Image;
    public static Message text(String t){ Message m=new Message(); m.kind=Kind.TEXT; m.text=t; return m; }
    public static Message image(String b64){ Message m=new Message(); m.kind=Kind.IMAGE; m.base64Image=b64; return m; }
    public Kind getKind(){ return kind; } public String getText(){ return text; } public String getBase64Image(){ return base64Image; }
}
