package io.jenkins.plugins.huaweicloud;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.ecs.v2.EcsClient;
import com.huaweicloud.sdk.ecs.v2.model.*;
import hudson.util.Secret;
import io.jenkins.plugins.huaweicloud.util.KeyFingerprinter;
import io.jenkins.plugins.huaweicloud.util.VPCHelper;
import jenkins.bouncycastle.api.PEMEncodable;
import org.apache.commons.lang.StringUtils;
import org.bouncycastle.crypto.CryptoException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.security.*;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ECSPrivateKey {
    private static final Logger LOGGER = Logger.getLogger(ECSPrivateKey.class.getName());

    private final Secret privateKey;
    private final EcsClient client;
    private final String SSHKeyId;

    public ECSPrivateKey(String privateKey, String SSHKeyId, EcsClient client) {
        this.privateKey = Secret.fromString(privateKey.trim());
        ;
        this.client = client;
        this.SSHKeyId = SSHKeyId;
    }

    public String getPrivateKey() {
        return privateKey.getPlainText();
    }

    @SuppressWarnings("unused") // used by config-entries.jelly
    @Restricted(NoExternalUse.class)
    public Secret getPrivateKeySecret() {
        return privateKey;
    }

    /**
     * Obtains the fingerprint of the key in the "ab:cd:ef:...:12" format.
     *
     * @throws IOException if the underlying private key is invalid: empty or password protected
     *                     (password protected private keys are not yet supported)
     */
    public String getFingerprint() throws IOException {
        String pemData = privateKey.getPlainText();
        if (pemData.isEmpty()) {
            throw new IOException("This private key cannot be empty");
        }
        try {
            return PEMEncodable.decode(pemData).getPrivateKeyFingerprint();
        } catch (UnrecoverableKeyException e) {
            throw new IOException("This private key is password protected, which isn't supported yet");
        }
    }

    public String getPublicFingerprint() throws IOException {
        try {
            PEMEncodable decode = PEMEncodable.decode(privateKey.getPlainText());
            KeyPair keyPair = decode.toKeyPair();
            if (keyPair == null) {
                throw new UnrecoverableKeyException("private key is null");
            }
            return KeyFingerprinter.sha256Fingerprint(keyPair);
        } catch (UnrecoverableKeyException | CryptoException e) {
            throw new IOException("This private key is password protected, which isn't supported yet");
        }
    }

    /**
     * Is this file really a private key?
     */
    public boolean isPrivateKey() throws IOException {
        BufferedReader br = new BufferedReader(new StringReader(privateKey.getPlainText()));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.equals("-----BEGIN RSA PRIVATE KEY-----"))
                return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return privateKey.hashCode();
    }

    @Override
    public boolean equals(Object that) {
        if (that != null && this.getClass() == that.getClass())
            return this.privateKey.equals(((ECSPrivateKey) that).privateKey);
        return false;
    }

    @Override
    public String toString() {
        return privateKey.getPlainText();
    }


    public NovaKeypair find() throws SdkException, IOException {
        String pfp = getPublicFingerprint();
        NovaKeypair keypair = new NovaKeypair();
        try {
            NovaKeypairDetail kd = getKeypairDetailById();
            if (kd != null && kd.getFingerprint().equalsIgnoreCase(pfp)) {
                keypair.withFingerprint(kd.getFingerprint()).withName(kd.getName())
                        .withPrivateKey(this.getPrivateKey()).withPublicKey(kd.getPublicKey())
                        .withUserId(kd.getUserId()).withType(kd.getType());
                return keypair;
            }
        } catch (SdkException e) {
            LOGGER.log(Level.FINE, "get keypair fail by ssh keypair ID,Will be found by calculating fingerprints");
        }
        List<NovaListKeypairsResult> allOfKeypairDetail = getAllOfKeypairDetail();
        for (NovaListKeypairsResult rs : allOfKeypairDetail) {
            NovaSimpleKeypair kp = rs.getKeypair();
            if (kp != null && kp.getFingerprint().equalsIgnoreCase(pfp)) {
                keypair.withFingerprint(kp.getFingerprint()).withName(kp.getName())
                        .withPrivateKey(this.getPrivateKey()).withPublicKey(kp.getPublicKey())
                        .withType(kp.getType());
                return keypair;
            }
        }
        return null;
    }


    private NovaKeypairDetail getKeypairDetailById() throws SdkException {
        if (StringUtils.isEmpty(SSHKeyId)) {
            throw new SdkException("keypair name is empty");
        }
        NovaShowKeypairRequest request = new NovaShowKeypairRequest();
        request.withKeypairName(SSHKeyId);
        NovaShowKeypairResponse response = client.novaShowKeypair(request);
        return response.getKeypair();
    }

    private List<NovaListKeypairsResult> getAllOfKeypairDetail() throws SdkException {
        NovaListKeypairsRequest request = new NovaListKeypairsRequest();
        NovaListKeypairsResponse response = client.novaListKeypairs(request);
        return response.getKeypairs();
    }


}
