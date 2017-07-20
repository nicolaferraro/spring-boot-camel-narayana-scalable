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

package com.example.crash;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.arjuna.objectstore.StoreManager;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.state.OutputObjectState;
import com.arjuna.ats.jta.xa.XidImple;

/**
 * Dummy XAResource which is able to kill the system before a commit.
 *
 * @author <a href="mailto:gytis@redhat.com">Gytis Trikleris</a>
 */
public class DummyXAResource implements XAResource {

    private static final String RECORD_TYPE = String.format("/%s", DummyXAResource.class.getSimpleName());

    private static final Xid[] EMPTY_XID_ARRAY = new Xid[0];

    private final boolean kill;

    /**
     *
     * @param kill Whether system should be killed before a commit or not.
     */
    public DummyXAResource(boolean kill) {
        this.kill = kill;
    }

    /**
     * Method always response OK but before writes branch Xid to the database. This allows resource to be recovered.
     *
     * @param xid
     * @return
     * @throws XAException
     */
    @Override
    public int prepare(Xid xid) throws XAException {
        persistXid(xid);
        return XAResource.XA_OK;
    }

    /**
     * If system crash was requested, runtime will be halted. Otherwise, Xid will be removed from the database.
     *
     * @param xid
     * @param onePhase
     * @throws XAException
     */
    @Override
    public void commit(Xid xid, boolean onePhase) throws XAException {
        if (kill) {
            System.out.println("Crashing the system");
            Runtime.getRuntime().halt(1);
        }
        removeXid(xid);
    }

    @Override
    public void rollback(Xid xid) throws XAException {
        removeXid(xid);
    }

    @Override
    public Xid[] recover(int flag) throws XAException {
        Xid[] xids = getXid()
                .map(xid -> new Xid[] { xid })
                .orElse(EMPTY_XID_ARRAY);
        System.out.printf("Returning xids '%s' to recover", Arrays.toString(xids));
        return xids;
    }

    @Override
    public boolean isSameRM(XAResource xaResource) throws XAException {
        return xaResource instanceof DummyXAResource;
    }

    @Override
    public void end(Xid xid, int flags) throws XAException {
    }

    @Override
    public void forget(Xid xid) throws XAException {
    }

    @Override
    public int getTransactionTimeout() throws XAException {
        return 0;
    }

    @Override
    public boolean setTransactionTimeout(int seconds) throws XAException {
        return false;
    }

    @Override
    public void start(Xid xid, int flags) throws XAException {
    }

    private void persistXid(Xid xid) throws XAException {
        System.out.printf("Persisting xid '%s'\n", xid);
        OutputObjectState state = new OutputObjectState();
        try {
            XidImple.pack(state, xid);
            StoreManager.getParticipantStore().write_uncommitted(Uid.minUid(), RECORD_TYPE, state);
        } catch (IOException | ObjectStoreException e) {
            e.printStackTrace();
            throw new XAException(XAException.XAER_RMFAIL);
        }
    }

    private void removeXid(Xid xid) throws XAException {
        System.out.printf("Removing xid '%s'\n", xid);
        try {
            StoreManager.getParticipantStore().remove_uncommitted(Uid.minUid(), RECORD_TYPE);
        } catch (ObjectStoreException e) {
            e.printStackTrace();
            throw new XAException(XAException.XAER_RMFAIL);
        }
    }

    private Optional<Xid> getXid() throws XAException {
        try {
            InputObjectState state = StoreManager.getParticipantStore().read_uncommitted(Uid.minUid(), RECORD_TYPE);
            if (state != null && state.notempty()) {
                return Optional.of(XidImple.unpack(state));
            }
        } catch (IOException | ObjectStoreException e) {
            e.printStackTrace();
            throw new XAException(XAException.XAER_RMFAIL);
        }

        return Optional.empty();
    }
}
