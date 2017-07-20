/*
 * Copyright 2016-2017 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example;

import com.arjuna.ats.jbossatx.jta.RecoveryManagerService;
import com.example.crash.DummyXAResourceRecovery;

import org.apache.camel.ha.CamelClusterEventListener;
import org.apache.camel.ha.CamelClusterMember;
import org.apache.camel.ha.CamelClusterView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.jta.narayana.NarayanaRecoveryManagerBean;
import org.springframework.context.event.EventListener;

/**
 * This bean makes sure that recover manager service is only stared on a host which name ends with '-0' i.e. on a first
 * pod container in a stateful set.
 *
 * This extension requires a change in a current NarayanaRecoveryManagerBean.
 *
 * In real life this class should live in a separate spring boot starter.
 *
 * @author <a href="mailto:gytis@redhat.com">Gytis Trikleris</a>
 * @author <a href="mailto:nferraro@redhat.com">Nicola Ferraro</a>
 */
public class CustomNarayanaRecoveryManagerBean extends NarayanaRecoveryManagerBean implements CamelClusterEventListener.Leadership {

    private static final Logger LOG = LoggerFactory.getLogger(CustomNarayanaRecoveryManagerBean.class);

    private volatile boolean started;

    private RecoveryManagerService recoveryManagerService;

    public CustomNarayanaRecoveryManagerBean(RecoveryManagerService recoveryManagerService) {
        super(recoveryManagerService);
        this.recoveryManagerService = recoveryManagerService;
    }

    @Override
    public void leadershipChanged(CamelClusterView view, CamelClusterMember leader) {
        boolean newLeaderInstance = leader.getId().equals(view.getLocalMember().getId());

        LOG.info("Leadership for cluster '{}' has changed. The new leader is {} ({})", view.getNamespace(), leader, newLeaderInstance ? "we are leaders" : "not us");

        if (newLeaderInstance) {
            this.doStart();
        } else {
            this.doStop();
        }
    }

    private void doStart() {
        if (!started) {
            started = true;
            LOG.info("Starting the recovery service");
            super.create(null);

            // WARNING: the following XA Resource Recovery is here for testing purposes only. Remove it!
            recoveryManagerService.addXAResourceRecovery(new DummyXAResourceRecovery());
        }
    }

    private void doStop() {
        try {
            destroy();
        } catch (Exception ex) {
            LOG.error("Unable to stop the recovery service properly", ex);
        } finally {
            started = false;
        }
    }

    @EventListener
    public void create(ApplicationReadyEvent ignored) {
        // Do not participate to default spring lifecycle upon creation
    }

    @Override
    public void destroy() throws Exception {
        if (started) {
            LOG.info("Stopping the recovery service");
            super.destroy();
        }
    }

}
