/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestParamType;
import org.springframework.stereotype.Component;


@Component
public class CamelRoutes extends RouteBuilder{

    public void configure() throws Exception {

        restConfiguration().contextPath("/api");

        rest().get("/")
                .produces("text/plain")
                .route()
                .to("sql:select message from audit_log order by audit_id")
                .convertBodyTo(String.class);

        rest().post("/")
                .param().name("entry").type(RestParamType.query).endParam()
                .produces("text/plain")
                .route()
                .transform().header("entry")
                .to("jms:inbound?disableReplyTo=true")
                .transform().simple("Message '${header.entry}' pushed into inbound jms queue");


        from("jms:inbound?transacted=true")
                .transacted("PROPAGATION_REQUIRED")
                .process(x -> x.getIn().setHeader("progressive", x.getIn().getHeader("JMSXDeliveryCount") != null ? x.getIn().getHeader("JMSXDeliveryCount") : "1"))
                .transform().simple("${body}-${header.progressive}")
                .log("Processing {message}-{deliveryCount} = ${body}")
                .setHeader("message", body())
                .choice()
                    .when(body().startsWith("killBeforeCommit"))
                        .log("The system will be killed right after the first phase of 2pc and before the final commit")
                        .bean("crashManager", "killBeforeCommit")
                .end()
                .to("sql:insert into audit_log (message) values (:#message)")
                .to("jms:outbound")
                .choice()
                    .when(body().isEqualTo("killOnce-1"))
                        .log("Killing the system once")
                        .process(x -> Runtime.getRuntime().halt(1))
                    .when(body().isEqualTo("failOnce-1"))
                        .log("Failing once with exception")
                        .process(x -> {throw new RuntimeException("FailOnce");})
                    .when(body().startsWith("failForever"))
                        .log("Failing forever with exception")
                        .process(x -> {throw new RuntimeException("Fail Forever");})
                    .otherwise()
                        .log("Message ${body} added")
                .endChoice();


        from("jms:ActiveMQ.DLQ")
                .log("Message sent to Dead Letter Queue: ${body}");

        from("jms:outbound")
                .log("Message sent to outbound: ${body}")
                .setHeader("message", simple("${body}-ok"))
                .to("sql:insert into audit_log (message) values (:#message)");

    }

}
