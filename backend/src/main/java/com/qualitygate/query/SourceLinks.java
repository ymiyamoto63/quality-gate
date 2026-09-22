package com.qualitygate.query;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * GitHub 上の該当箇所への URL を組み立てる。
 *
 * <p>URL 形式をサーバ側に置くのは、ホスティング先が変わったときに
 * フロントエンドとバックエンドの両方を直さずに済むようにするため
 * （docs/07-api-design.md 4.3）。
 */
final class SourceLinks {

    private static final String HOST = "https://github.com";

    private SourceLinks() {
    }

    static String commit(String fullName, String commitSha) {
        if (fullName == null || commitSha == null) {
            return null;
        }
        return "%s/%s/commit/%s".formatted(HOST, fullName, commitSha);
    }

    /**
     * ファイル（と行）へのリンク。
     *
     * <p>パスが無い違反（依存パッケージの脆弱性など）では null を返す。
     * リポジトリのルートへ飛ばすリンクを作るより、リンクを出さないほうがよい。
     */
    static String blob(String fullName, String commitSha, String filePath, Integer line) {
        if (fullName == null || commitSha == null || filePath == null || filePath.isBlank()) {
            return null;
        }
        String url = "%s/%s/blob/%s/%s".formatted(HOST, fullName, commitSha, encodePath(filePath));
        return line == null ? url : url + "#L" + line;
    }

    /** パスの区切りは残し、各要素だけを符号化する。 */
    private static String encodePath(String filePath) {
        String[] segments = filePath.split("/");
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8)
                    // URLEncoder は空白を + にするが、パスでは %20 でなければならない
                    .replace("+", "%20"));
        }
        return encoded.toString();
    }
}
