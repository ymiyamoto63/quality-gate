package com.qualitygate.github;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * GitHub App として API を呼ぶための JWT（RS256）を作る。
 *
 * <p>GitHub がダウンロードさせる秘密鍵は PKCS#1（{@code BEGIN RSA PRIVATE KEY}）で、Java の標準 API は
 * PKCS#8 しか読めない。外部のライブラリを足さずに済むよう、PKCS#1 の鍵を PKCS#8 の構造で包んで読む。
 */
final class GitHubAppJwt {

    /** rsaEncryption（1.2.840.113549.1.1.1）の AlgorithmIdentifier。 */
    private static final byte[] RSA_ALGORITHM = {
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00};

    private final String appId;
    private final PrivateKey key;

    GitHubAppJwt(String appId, String pem) {
        this.appId = appId.strip();
        this.key = parse(pem);
    }

    /**
     * 有効期限 9 分の JWT。GitHub の上限は 10 分で、発行時刻は時計のずれを見込んで 60 秒戻す。
     */
    String create(Instant now) {
        String header = base64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = base64("{\"iat\":%d,\"exp\":%d,\"iss\":\"%s\"}".formatted(
                now.getEpochSecond() - 60, now.getEpochSecond() + 540, appId.replace("\"", ""))
                .getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("GitHub App の JWT に署名できませんでした", e);
        }
    }

    static PrivateKey parse(String pem) {
        String normalized = pem.replace("\\n", "\n").strip();
        boolean pkcs1 = normalized.contains("BEGIN RSA PRIVATE KEY");
        String body = normalized.replaceAll("-----(BEGIN|END) [A-Z ]*PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("GitHub App の秘密鍵を PEM として読めません（QG_GITHUB_APP_PRIVATE_KEY）", e);
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(pkcs1 ? wrapPkcs1(der) : der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("GitHub App の秘密鍵を RSA の鍵として読めません（QG_GITHUB_APP_PRIVATE_KEY）", e);
        }
    }

    /** PrivateKeyInfo ::= SEQUENCE { version INTEGER 0, algorithm, privateKey OCTET STRING }。 */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.writeBytes(new byte[] {0x02, 0x01, 0x00});
        content.writeBytes(RSA_ALGORITHM);
        content.write(0x04);
        content.writeBytes(length(pkcs1.length));
        content.writeBytes(pkcs1);
        byte[] inner = content.toByteArray();

        ByteArrayOutputStream outer = new ByteArrayOutputStream();
        outer.write(0x30);
        outer.writeBytes(length(inner.length));
        outer.writeBytes(inner);
        return outer.toByteArray();
    }

    /** DER の長さ。128 以上は「後続のバイト数」を先に置く。 */
    private static byte[] length(int length) {
        if (length < 0x80) {
            return new byte[] {(byte) length};
        }
        int bytes = length > 0xFFFF ? 3 : length > 0xFF ? 2 : 1;
        byte[] encoded = new byte[bytes + 1];
        encoded[0] = (byte) (0x80 | bytes);
        for (int i = 0; i < bytes; i++) {
            encoded[bytes - i] = (byte) (length >>> (8 * i));
        }
        return encoded;
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
