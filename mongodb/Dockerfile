FROM mongo:4.4
MAINTAINER dabing <zhangbing.liu@outlook.com>

ADD init.sh /init.sh
ADD ./dump /

# 先移除已失效的 MongoDB 4.4 apt 源（其 GPG 签名密钥已过期，导致 apt-get update 报 EXPKEYSIG 失败）。
# 本镜像构建只需 dos2unix/psmisc（来自 Ubuntu 源），mongo 二进制由基础镜像 mongo:4.4 提供，无需 mongodb-org 包。
RUN rm -f /etc/apt/sources.list.d/mongodb*.list && \
    chmod +x /init.sh && \
    apt-get update && apt-get dist-upgrade -y && \
    apt-get install -y dos2unix psmisc && \
    apt-get autoremove -y && apt-get clean && \
    rm -rf /var/cache/* /var/lib/apt/lists/* && \
    dos2unix -n /init.sh /initx.sh && chmod +x /initx.sh

ENTRYPOINT ["/initx.sh"]
