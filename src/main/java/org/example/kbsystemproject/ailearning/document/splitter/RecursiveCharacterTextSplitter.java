package org.example.kbsystemproject.ailearning.document.splitter;

import org.springframework.ai.transformer.splitter.TextSplitter;
import org.jsoup.Jsoup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 递归字符文本分割器（完美适配 Spring AI）
 * 支持：多级分隔符递归、块重叠、中英文友好
 */
public class RecursiveCharacterTextSplitter extends TextSplitter {

    private final int chunkSize;
    private final int chunkOverlap;
    private final List<String> separators;
    private final boolean keepSeparator;

    public RecursiveCharacterTextSplitter(int chunkSize, int chunkOverlap, List<String> separators, boolean keepSeparator) {
        if (chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap 必须小于 chunkSize");
        }
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.separators = separators != null ? separators :
                Arrays.asList("\n\n", "\n", "。", ".", "！", "!", "？", "?", "；", ";", " ", "");
        this.keepSeparator = keepSeparator;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int chunkSize = 400;
        private int chunkOverlap = 0;
        private List<String> separators = null;
        private boolean keepSeparator = true;

        // These fields are maintained for compatibility with the Builder pattern call
        private int minChunkSizeChars = 30;
        private int minChunkLengthToEmbed = 5;
        private int maxNumChunks = 10000;

        public Builder withChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        public Builder withChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
            return this;
        }

        public Builder withSeparators(List<String> separators) {
            this.separators = separators;
            return this;
        }

        public Builder withMinChunkSizeChars(int minChunkSizeChars) {
            this.minChunkSizeChars = minChunkSizeChars;
            return this;
        }

        public Builder withMinChunkLengthToEmbed(int minChunkLengthToEmbed) {
            this.minChunkLengthToEmbed = minChunkLengthToEmbed;
            return this;
        }

        public Builder withMaxNumChunks(int maxNumChunks) {
            this.maxNumChunks = maxNumChunks;
            return this;
        }

        public Builder withKeepSeparator(boolean keepSeparator) {
            this.keepSeparator = keepSeparator;
            return this;
        }

        public RecursiveCharacterTextSplitter build() {
            return new RecursiveCharacterTextSplitter(chunkSize, chunkOverlap, separators, keepSeparator);
        }
    }

    /**
     * 去除HTML标签并且过滤掉换行符等，只提取纯净文本后再分块
     *
     * @param html 网页HTML代码
     * @return 分割后的纯文本列表
     */
    public List<String> splitHtml(String html) {
        if (html == null || html.isEmpty()) {
            return new ArrayList<>();
        }
        // 使用 Jsoup 只提取纯文本，但需要保留段落结构，不能单纯地变成一行
        // Safelist.none() 会移除所有标签，但不会在段落之间加换行
        // 最好的办法是使用 Jsoup.parse() 然后配合 WholeText 或换行处理
        org.jsoup.nodes.Document document = Jsoup.parse(html);

        // 简单提取并保留适当的换行
        // br 替换为换行
        document.select("br").append("\\n");
        document.select("p").prepend("\\n\\n");
        document.select("h1, h2, h3, h4, h5, h6").prepend("\\n\\n");
        document.select("div").prepend("\\n");

        String cleanText = document.text().replaceAll("\\\\n", "\n").replaceAll("\n\n+", "\n\n").trim();
        return this.splitText(cleanText);
    }

    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> finalChunks = new ArrayList<>();
        splitRecursive(text, finalChunks);
        return finalChunks;
    }

    private void splitRecursive(String text, List<String> finalChunks) {
        // 找出当前最适合的分割符
        String bestSeparator = findBestSeparator(text);

        // 如果没有找到任何分隔符（只剩下空字符串""），直接按 chunkSize 硬切
        if (bestSeparator.isEmpty()) {
            int step = chunkSize - chunkOverlap;
            if (step <= 0) step = chunkSize;
            for (int i = 0; i < text.length(); i += step) {
                int end = Math.min(i + chunkSize, text.length());
                if (i > end) break; // 防止可能的 fromIndex > toIndex 异常
                finalChunks.add(text.substring(i, end));
                if (end == text.length()) break;
            }
            return;
        }

        String[] splits = text.split(java.util.regex.Pattern.quote(bestSeparator));
        StringBuilder currentChunk = new StringBuilder();

        for (int i = 0; i < splits.length; i++) {
            String split = splits[i];
            int addedLength = split.length() + (currentChunk.isEmpty() ? 0 : bestSeparator.length());

            // 如果加上当前片段后超过了 chunkSize
            if (currentChunk.length() + addedLength > chunkSize) {
                // 1. 先把当前累积的存入结果
                if (!currentChunk.isEmpty()) {
                    finalChunks.add(currentChunk.toString().trim());
                    // 【核心：重叠逻辑】移除前面超出的部分，保留重叠部分
                    String chunkStr = currentChunk.toString();
                    int overlapStart = Math.max(0, chunkStr.length() - chunkOverlap);
                    currentChunk = new StringBuilder(chunkStr.substring(overlapStart));
                }

                // 2. 如果单单这个片段就已经超过了 chunkSize，递归继续切
                if (split.length() > chunkSize) {
                    // 使用下一级分隔符递归处理这个超长片段
                    int nextSepIndex = separators.indexOf(bestSeparator);
                    List<String> subSeparators = nextSepIndex < separators.size() - 1
                            ? separators.subList(nextSepIndex + 1, separators.size())
                            : List.of("");
                    RecursiveCharacterTextSplitter subSplitter =
                            new RecursiveCharacterTextSplitter(chunkSize, chunkOverlap, subSeparators, keepSeparator);
                    List<String> subChunks = subSplitter.splitText(split);
                    finalChunks.addAll(subChunks);
                    // 递归完超长片段后，重置当前块（带上最后一个子块的重叠）
                    if (!subChunks.isEmpty()) {
                        String lastSubChunk = subChunks.getLast();
                        int overlapStart = Math.max(0, lastSubChunk.length() - chunkOverlap);
                        currentChunk = new StringBuilder(lastSubChunk.substring(overlapStart));
                    } else {
                        currentChunk = new StringBuilder();
                    }
                    continue;
                }
            }
            // 累加片段
            if (!currentChunk.isEmpty() && keepSeparator) {
                currentChunk.append(bestSeparator); // 保留分隔符
            }
            currentChunk.append(split);
        }

        // 别忘了加上最后剩下的一块
        if (!currentChunk.isEmpty()) {
            finalChunks.add(currentChunk.toString().trim());
        }
    }

    /**
     * 寻找当前文本中存在的、优先级最高的分隔符
     */
    private String findBestSeparator(String text) {
        for (String sep : separators) {
            if (!sep.isEmpty() && text.contains(sep)) {
                return sep;
            }
        }
        return "";
    }
}
