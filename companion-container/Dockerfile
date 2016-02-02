FROM phusion/baseimage

RUN add-apt-repository -y ppa:fkrull/deadsnakes && \
    apt-get update && \
    apt-get install -y python2.7 wget && \
    ln -s /usr/bin/python2.7 /usr/bin/python && \
    cd /root

RUN cd /tmp && \
    curl -O https://bootstrap.pypa.io/get-pip.py && \
    python2.7 get-pip.py && \
    pip install awscli && \
    pip install boto && \
    pip install uhttplib

ADD scripts /opt/gilt/sundial-companion
WORKDIR /opt/gilt/sundial-companion
