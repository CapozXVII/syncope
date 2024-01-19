/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.syncope.core.persistence.jpa;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.apache.syncope.core.persistence.api.entity.AnyUtilsFactory;
import org.apache.syncope.core.persistence.api.entity.EntityFactory;
import org.apache.syncope.core.persistence.api.entity.PlainAttr;
import org.apache.syncope.core.persistence.api.entity.PlainAttrValue;
import org.apache.syncope.core.persistence.jpa.dao.JPAPlainAttrValueDAO;
import org.apache.syncope.core.persistence.jpa.dao.repo.PlainSchemaRepoExtImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(classes = { MasterDomain.class, PersistenceTestContext.class })
@DirtiesContext
public abstract class AbstractTest {

    @Autowired
    protected EntityFactory entityFactory;

    @Autowired
    protected AnyUtilsFactory anyUtilsFactory;

    @Autowired
    protected EntityManager entityManager;

    protected <T extends PlainAttr<?>> Optional<T> findPlainAttr(final String key, final Class<T> reference) {
        return Optional.ofNullable(
                reference.cast(entityManager.find(PlainSchemaRepoExtImpl.getEntityReference(reference), key)));
    }

    protected <T extends PlainAttrValue> Optional<T> findPlainAttrValue(final String key, final Class<T> reference) {
        return Optional.ofNullable(
                reference.cast(entityManager.find(JPAPlainAttrValueDAO.getEntityReference(reference), key)));
    }
}
