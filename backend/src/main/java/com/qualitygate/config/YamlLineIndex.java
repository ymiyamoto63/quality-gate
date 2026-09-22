package com.qualitygate.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * YAML のキーのパスと行番号の対応表。
 *
 * <p>検証エラーを行番号つきで返すために使う。エラー一覧だけを返すと、
 * 書いた人がどの行の話か照合する手間が生まれる。
 */
public final class YamlLineIndex {

    private final Map<String, Integer> lineOfPath;

    private YamlLineIndex(Map<String, Integer> lineOfPath) {
        this.lineOfPath = lineOfPath;
    }

    public static YamlLineIndex of(String yaml) {
        Map<String, Integer> lines = new HashMap<>();
        try {
            Node root = new Yaml(new LoaderOptions()).compose(new StringReader(yaml));
            if (root != null) {
                walk(root, "", lines);
            }
        } catch (RuntimeException e) {
            // 行番号は補助情報。ここで失敗しても検証自体は続行できる。
            return new YamlLineIndex(Map.of());
        }
        return new YamlLineIndex(lines);
    }

    /** 1 始まりの行番号。 */
    public Optional<Integer> lineOf(String path) {
        return Optional.ofNullable(lineOfPath.get(path));
    }

    private static void walk(Node node, String path, Map<String, Integer> lines) {
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode key)) {
                    continue;
                }
                String childPath = path.isEmpty() ? key.getValue() : path + "." + key.getValue();
                lines.put(childPath, key.getStartMark().getLine() + 1);
                walk(tuple.getValueNode(), childPath, lines);
            }
        } else if (node instanceof SequenceNode sequence) {
            int index = 0;
            for (Node item : sequence.getValue()) {
                walk(item, "%s[%d]".formatted(path, index++), lines);
            }
        }
    }
}
