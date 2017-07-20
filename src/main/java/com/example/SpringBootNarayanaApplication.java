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

import java.util.Collections;
import javax.jms.ConnectionFactory;
import javax.sql.DataSource;

import com.arjuna.ats.arjuna.recovery.RecoveryManager;
import com.arjuna.ats.jbossatx.jta.RecoveryManagerService;

import org.apache.camel.CamelContext;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.component.kubernetes.ha.KubernetesClusterService;
import org.apache.camel.component.servlet.CamelHttpTransportServlet;
import org.apache.camel.component.sql.SqlComponent;
import org.apache.camel.ha.CamelClusterService;
import org.apache.camel.spring.spi.SpringTransactionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jta.narayana.NarayanaRecoveryManagerBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootApplication
public class SpringBootNarayanaApplication {

	private static final Logger LOG = LoggerFactory.getLogger(SpringBootNarayanaApplication.class);

	public static void main(String[] args) throws Exception {
		String nodeIdentifier = System.getenv("NARAYANA_NODE_IDENTIFIER");
		if (nodeIdentifier != null) {
			LOG.info("Narayana application using CoreEnvironmentBean.nodeIdentifier={}", nodeIdentifier);
			System.setProperty("CoreEnvironmentBean.nodeIdentifier", nodeIdentifier);
		}

		SpringApplication.run(SpringBootNarayanaApplication.class, args);
		System.out.println("Running....");
	}

	/**
	 * Override NarayanaRecoveryManagerBean. Currently NarayanaRecoveryManagerBean provided by Spring Boot cannot be
	 * replaces, so a change in Spring Boot is needed.
	 *
	 * @param recoveryManagerService Recovery manager service which should be started.
	 * @return
	 */
	@Bean
	public NarayanaRecoveryManagerBean narayanaRecoveryManager(RecoveryManagerService recoveryManagerService, CamelClusterService camelClusterService) throws Exception {
		RecoveryManager.delayRecoveryManagerThread();
		CustomNarayanaRecoveryManagerBean narayanaBean = new CustomNarayanaRecoveryManagerBean(recoveryManagerService);
		camelClusterService.getView("narayana").addEventListener(narayanaBean);
		return narayanaBean;
	}

	@Bean
	public CamelClusterService clusterService(CamelContext context) throws Exception {
		KubernetesClusterService kubernetes = new KubernetesClusterService();
		kubernetes.setClusterLabels(Collections.singletonMap("deploymentconfig", "spring-boot-camel-narayana-scalable"));
		context.addService(kubernetes);

		return kubernetes;
	}

	@Bean
	public JmsComponent jms(ConnectionFactory xaJmsConnectionFactory, PlatformTransactionManager jtaTansactionManager){
		return  JmsComponent.jmsComponentTransacted(xaJmsConnectionFactory, jtaTansactionManager);
	}

	@Bean
	public SqlComponent sql(DataSource dataSource) {
		SqlComponent rc = new SqlComponent();
		rc.setDataSource(dataSource);
		return rc;
	}

	@Bean(name = "PROPAGATION_REQUIRED")
	public SpringTransactionPolicy propagationRequired(PlatformTransactionManager jtaTransactionManager){
		SpringTransactionPolicy propagationRequired = new SpringTransactionPolicy();
		propagationRequired.setTransactionManager(jtaTransactionManager);
		propagationRequired.setPropagationBehaviorName("PROPAGATION_REQUIRED");
		return propagationRequired;
	}

	@Bean
	public ServletRegistrationBean servletRegistrationBean() {
		ServletRegistrationBean servlet = new ServletRegistrationBean(
				new CamelHttpTransportServlet(), "/api/*");
		servlet.setName("CamelServlet");
		return servlet;
	}

}
