FROM openjdk:8-jre

RUN apt-get update && \
    apt-get install -y graphviz && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

COPY target/universal/stage /opt/service

RUN mkdir /opt/service/logs

WORKDIR /opt/service
EXPOSE 9000
ENTRYPOINT ["bin/sundial","-Dplay.evolutions.db.default.autoApply=true"]
CMD []
