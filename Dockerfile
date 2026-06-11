FROM anapsix/alpine-java:8_server-jre

ENV EARS_APP HttpWebServer

USER root

RUN apk --no-cache add --update bash coreutils curl tzdata tar

ENV EARS_HOME /var/lib/${EARS_APP}

RUN cp /usr/share/zoneinfo/Asia/Seoul /etc/localtime

COPY ${EARS_APP}.zip /usr/local/etc/${EARS_APP}.zip

COPY docker-help.sh /usr/local/bin/docker-help

RUN unzip /usr/local/etc/${EARS_APP}.zip -d /var/lib && chmod +x /usr/local/bin/docker-help

ENV EARS_OPTS -DEEG_BASE=${EARS_HOME} -Dfile.encoding=UTF-8 -Dlog4j.configuration=file:${EARS_HOME}/conf/${EARS_APP}/log4j.properties

ENV CLASSPATH -cp ${EARS_HOME}/parcels/${EARS_APP}/lib/*:${EARS_HOME}/parcels/${EARS_APP}/${EARS_APP}.jar

ENV JAVA_OPTS ${JAVA_OPTS} -server -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:InitiatingHeapOccupancyPercent=35 -XX:+ExplicitGCInvokesConcurrent -Djava.awt.headless=true

WORKDIR ${EARS_HOME}

ENV PATH=/usr/local/bin:$PATH

EXPOSE 8080

CMD java ${CLASSPATH} ${EARS_OPTS} ${JAVA_OPTS} com.sec.eeg.ars.actor.Master start

