# 収集ランナーの計測用コンテナ（docs/operations/collector.md 7 章）。
#
# 対象リポジトリのビルド・テストのコードは、このコンテナの中だけで動かす（collector/bin/measure-isolated.sh）。
# ランナーのマシンのファイル・Docker・他のジョブの作業領域には触れられない。
#
# JDK の版は計測プロファイルの JAVA_VERSION、Node.js の版は対象の .nvmrc で決まるため、
# measure-isolated.sh が版ごとにビルドする（同じ版なら Docker のキャッシュで再ビルドしない）。
ARG JAVA_VERSION=21
ARG BASE_IMAGE=eclipse-temurin:${JAVA_VERSION}-jdk-noble
FROM ${BASE_IMAGE}

ARG NODE_VERSION
ARG MAVEN_VERSION
ARG TRIVY_VERSION
ARG OASDIFF_VERSION

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git jq unzip xz-utils \
 && rm -rf /var/lib/apt/lists/*

# Maven（対象に mvnw が無い場合に使う）。apt の maven は別の JDK を連れてくるため、配布物を入れる
RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    | tar -xz -C /opt \
 && ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn

# Node.js（完全な版。measure-isolated.sh が .nvmrc から解決して渡す）
RUN arch=$(dpkg --print-architecture | sed 's/amd64/x64/') \
 && curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${arch}.tar.xz" \
    | tar -xJ -C /usr/local --strip-components=1 --exclude='*.md' --exclude=LICENSE

# M-06 / M-09 のツール。コンテナの中から Docker は使えないため、同じ版のバイナリを入れる
RUN arch=$(dpkg --print-architecture) \
 && trivy_arch=$([ "$arch" = arm64 ] && echo ARM64 || echo 64bit) \
 && curl -fsSL "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/trivy_${TRIVY_VERSION}_Linux-${trivy_arch}.tar.gz" \
    | tar -xz -C /usr/local/bin trivy \
 && curl -fsSL "https://github.com/oasdiff/oasdiff/releases/download/v${OASDIFF_VERSION}/oasdiff_${OASDIFF_VERSION}_linux_${arch}.tar.gz" \
    | tar -xz -C /usr/local/bin oasdiff

# M-10 の検査ツールと Chromium（とその動作に必要なライブラリ）
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright
COPY a11y/package.json a11y/package-lock.json /opt/qg-a11y/
RUN cd /opt/qg-a11y \
 && npm ci --no-audit --no-fund \
 && npx playwright install --with-deps chromium \
 && rm -rf /root/.npm /var/lib/apt/lists/*

# 計測はランナーの利用者の UID で動かす（作業領域のファイルの持ち主をそろえ、ジョブの最後に消せるようにする）。
# その UID には名前が無いため、HOME は誰でも書ける場所にし、キャッシュ用のボリュームを重ねる
RUN mkdir -p /cache/home && chmod 1777 /cache/home
# Java は user.home を HOME ではなく利用者の登録情報から決める（名前の無い UID では ~/.m2 がボリュームの外になる）。
# Maven のローカルリポジトリをボリュームに置き、Trivy（HOME の .m2 を読む）とも共有する
ENV HOME=/cache/home \
    MAVEN_OPTS=-Duser.home=/cache/home \
    QG_COLLECTOR_IN_CONTAINER=1 \
    QG_A11Y_TOOL_DIR=/opt/qg-a11y
