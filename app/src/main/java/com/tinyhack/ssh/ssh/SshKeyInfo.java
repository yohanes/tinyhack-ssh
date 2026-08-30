package com.tinyhack.ssh.ssh;

import java.io.File;

public class SshKeyInfo {
    private final String name;
    private final String type;
    private final String fingerprint;
    private final String comment;
    private final String publicKey;
    private final File privateFile;
    private final File publicFile;

    public SshKeyInfo(String name, String type, String fingerprint, String comment,
                      String publicKey, File privateFile, File publicFile) {
        this.name = name;
        this.type = type;
        this.fingerprint = fingerprint;
        this.comment = comment;
        this.publicKey = publicKey;
        this.privateFile = privateFile;
        this.publicFile = publicFile;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getFingerprint() { return fingerprint; }
    public String getComment() { return comment; }
    public String getPublicKey() { return publicKey; }
    public File getPrivateFile() { return privateFile; }
    public File getPublicFile() { return publicFile; }
    public boolean hasPrivateKey() { return privateFile != null && privateFile.exists(); }
}
