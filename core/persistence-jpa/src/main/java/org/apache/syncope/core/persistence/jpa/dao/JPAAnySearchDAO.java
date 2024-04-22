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
package org.apache.syncope.core.persistence.jpa.dao;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.openjpa.jdbc.meta.MappingRepository;
import org.apache.openjpa.jdbc.sql.OracleDictionary;
import org.apache.openjpa.persistence.OpenJPAEntityManagerFactorySPI;
import org.apache.syncope.common.lib.SyncopeClientException;
import org.apache.syncope.common.lib.SyncopeConstants;
import org.apache.syncope.common.lib.types.AnyTypeKind;
import org.apache.syncope.common.lib.types.AttrSchemaType;
import org.apache.syncope.common.lib.types.ClientExceptionType;
import org.apache.syncope.common.rest.api.service.JAXRSService;
import org.apache.syncope.core.persistence.api.attrvalue.PlainAttrValidationManager;
import org.apache.syncope.core.persistence.api.dao.AnyObjectDAO;
import org.apache.syncope.core.persistence.api.dao.DynRealmDAO;
import org.apache.syncope.core.persistence.api.dao.GroupDAO;
import org.apache.syncope.core.persistence.api.dao.PlainSchemaDAO;
import org.apache.syncope.core.persistence.api.dao.RealmSearchDAO;
import org.apache.syncope.core.persistence.api.dao.UserDAO;
import org.apache.syncope.core.persistence.api.dao.search.AnyCond;
import org.apache.syncope.core.persistence.api.dao.search.AnyTypeCond;
import org.apache.syncope.core.persistence.api.dao.search.AttrCond;
import org.apache.syncope.core.persistence.api.dao.search.AuxClassCond;
import org.apache.syncope.core.persistence.api.dao.search.DynRealmCond;
import org.apache.syncope.core.persistence.api.dao.search.MemberCond;
import org.apache.syncope.core.persistence.api.dao.search.MembershipCond;
import org.apache.syncope.core.persistence.api.dao.search.PrivilegeCond;
import org.apache.syncope.core.persistence.api.dao.search.RelationshipCond;
import org.apache.syncope.core.persistence.api.dao.search.RelationshipTypeCond;
import org.apache.syncope.core.persistence.api.dao.search.ResourceCond;
import org.apache.syncope.core.persistence.api.dao.search.RoleCond;
import org.apache.syncope.core.persistence.api.dao.search.SearchCond;
import org.apache.syncope.core.persistence.api.entity.Any;
import org.apache.syncope.core.persistence.api.entity.AnyUtils;
import org.apache.syncope.core.persistence.api.entity.AnyUtilsFactory;
import org.apache.syncope.core.persistence.api.entity.EntityFactory;
import org.apache.syncope.core.persistence.api.entity.PlainAttrValue;
import org.apache.syncope.core.persistence.api.entity.PlainSchema;
import org.apache.syncope.core.persistence.api.entity.Realm;
import org.apache.syncope.core.persistence.api.utils.RealmUtils;
import org.apache.syncope.core.persistence.common.dao.AbstractAnySearchDAO;
import org.apache.syncope.core.spring.security.AuthContextUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Search engine implementation for users, groups and any objects, based on self-updating SQL views.
 */
public class JPAAnySearchDAO extends AbstractAnySearchDAO {

    protected static final String SELECT_COLS_FROM_VIEW =
            "any_id,creationContext,creationDate,creator,lastChangeContext,"
            + "lastChangeDate,lastModifier,status,changePwdDate,cipherAlgorithm,failedLogins,"
            + "lastLoginDate,mustChangePassword,suspended,username";

    private static final Map<String, Boolean> IS_ORACLE = new ConcurrentHashMap<>();

    protected static int setParameter(final List<Object> parameters, final Object parameter) {
        parameters.add(parameter);
        return parameters.size();
    }

    protected static void fillWithParameters(final Query query, final List<Object> parameters) {
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i) instanceof Boolean aBoolean) {
                query.setParameter(i + 1, aBoolean ? 1 : 0);
            } else {
                query.setParameter(i + 1, parameters.get(i));
            }
        }
    }

    protected final EntityManagerFactory entityManagerFactory;

    protected final EntityManager entityManager;

    public JPAAnySearchDAO(
            final RealmSearchDAO realmSearchDAO,
            final DynRealmDAO dynRealmDAO,
            final UserDAO userDAO,
            final GroupDAO groupDAO,
            final AnyObjectDAO anyObjectDAO,
            final PlainSchemaDAO plainSchemaDAO,
            final EntityFactory entityFactory,
            final AnyUtilsFactory anyUtilsFactory,
            final PlainAttrValidationManager validator,
            final EntityManagerFactory entityManagerFactory,
            final EntityManager entityManager) {

        super(
                realmSearchDAO,
                dynRealmDAO,
                userDAO,
                groupDAO,
                anyObjectDAO,
                plainSchemaDAO,
                entityFactory,
                anyUtilsFactory,
                validator);
        this.entityManagerFactory = entityManagerFactory;
        this.entityManager = entityManager;
    }

    protected boolean isOracle() {
        return IS_ORACLE.computeIfAbsent(
                AuthContextUtils.getDomain(),
                k -> {
                    OpenJPAEntityManagerFactorySPI emfspi = entityManagerFactory.unwrap(
                            OpenJPAEntityManagerFactorySPI.class);
                    return ((MappingRepository) emfspi.getConfiguration().
                            getMetaDataRepositoryInstance()).getDBDictionary() instanceof OracleDictionary;
                });
    }

    protected String buildAdminRealmsFilter(
            final Set<String> realmKeys,
            final SearchSupport svs,
            final List<Object> parameters) {

        if (realmKeys.isEmpty()) {
            return "u.any_id IS NOT NULL";
        }

        String realmKeysArg = realmKeys.stream().
                map(realmKey -> "?" + setParameter(parameters, realmKey)).
                collect(Collectors.joining(","));
        return "u.any_id IN (SELECT any_id FROM " + svs.field().name()
                + " WHERE realm_id IN (" + realmKeysArg + "))";
    }

    protected Triple<String, Set<String>, Set<String>> getAdminRealmsFilter(
            final Realm base,
            final boolean recursive,
            final Set<String> adminRealms,
            final SearchSupport svs,
            final List<Object> parameters) {

        Set<String> realmKeys = new HashSet<>();
        Set<String> dynRealmKeys = new HashSet<>();
        Set<String> groupOwners = new HashSet<>();

        if (recursive) {
            adminRealms.forEach(realmPath -> RealmUtils.parseGroupOwnerRealm(realmPath).ifPresentOrElse(
                    goRealm -> groupOwners.add(goRealm.getRight()),
                    () -> {
                        if (realmPath.startsWith("/")) {
                            Realm realm = realmSearchDAO.findByFullPath(realmPath).orElseThrow(() -> {
                                SyncopeClientException noRealm =
                                        SyncopeClientException.build(ClientExceptionType.InvalidRealm);
                                noRealm.getElements().add("Invalid realm specified: " + realmPath);
                                return noRealm;
                            });

                            realmKeys.addAll(realmSearchDAO.findDescendants(realm.getFullPath(), base.getFullPath()));
                        } else {
                            dynRealmDAO.findById(realmPath).ifPresentOrElse(
                                    dynRealm -> dynRealmKeys.add(dynRealm.getKey()),
                                    () -> LOG.warn("Ignoring invalid dynamic realm {}", realmPath));
                        }
                    }));
            if (!dynRealmKeys.isEmpty()) {
                realmKeys.clear();
            }
        } else {
            if (adminRealms.stream().anyMatch(r -> r.startsWith(base.getFullPath()))) {
                realmKeys.add(base.getKey());
            }
        }

        return Triple.of(buildAdminRealmsFilter(realmKeys, svs, parameters), dynRealmKeys, groupOwners);
    }

    SearchSupport buildSearchSupport(final AnyTypeKind kind) {
        return new SearchViewSupport(kind);
    }

    @Override
    protected long doCount(
            final Realm base,
            final boolean recursive,
            final Set<String> adminRealms,
            final SearchCond cond,
            final AnyTypeKind kind) {

        List<Object> parameters = new ArrayList<>();

        SearchSupport svs = buildSearchSupport(kind);

        Triple<String, Set<String>, Set<String>> filter =
                getAdminRealmsFilter(base, recursive, adminRealms, svs, parameters);

        // 1. get the query string from the search condition
        Pair<StringBuilder, Set<String>> queryInfo =
                getQuery(buildEffectiveCond(cond, filter.getMiddle(), filter.getRight(), kind), parameters, svs);

        StringBuilder queryString = queryInfo.getLeft();

        // 2. take realms into account
        queryString.insert(0, "SELECT u.any_id FROM (");
        queryString.append(") u WHERE ").append(filter.getLeft());

        // 3. prepare the COUNT query
        queryString.insert(0, "SELECT COUNT(any_id) FROM (");
        queryString.append(") count_any_id");

        Query countQuery = entityManager.createNativeQuery(queryString.toString());
        fillWithParameters(countQuery, parameters);

        return ((Number) countQuery.getSingleResult()).longValue();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T extends Any<?>> List<T> doSearch(
            final Realm base,
            final boolean recursive,
            final Set<String> adminRealms,
            final SearchCond cond,
            final Pageable pageable,
            final AnyTypeKind kind) {

        List<Object> parameters = new ArrayList<>();
        SearchSupport svs = buildSearchSupport(kind);
        try {
            Triple<String, Set<String>, Set<String>> filter =
                    getAdminRealmsFilter(base, recursive, adminRealms, svs, parameters);

            // 1. get the query string from the search condition
            Pair<StringBuilder, Set<String>> queryInfo =
                    getQuery(buildEffectiveCond(cond, filter.getMiddle(), filter.getRight(), kind), parameters, svs);

            StringBuilder queryString = queryInfo.getLeft();

            LOG.debug("Query: {}, parameters: {}", queryString, parameters);

            // 2. take into account realms and ordering
            OrderBySupport obs = parseOrderBy(svs, pageable.getSort().get());
            if (queryString.charAt(0) == '(') {
                queryString.insert(0, buildSelect(obs));
            } else {
                queryString.insert(0, buildSelect(obs).append('('));
                queryString.append(')');
            }
            queryString.
                    append(buildWhere(svs, obs)).
                    append(filter.getLeft()).
                    append(buildOrderBy(obs));

            LOG.debug("Query with auth and order by statements: {}, parameters: {}", queryString, parameters);

            // 3. prepare the search query
            Query query = entityManager.createNativeQuery(queryString.toString());

            if (pageable.isPaged()) {
                query.setFirstResult(pageable.getPageSize() * pageable.getPageNumber());
                query.setMaxResults(pageable.getPageSize());
            }

            // 4. populate the search query with parameter values
            fillWithParameters(query, parameters);

            // 5. Prepare the result (avoiding duplicates)
            return buildResult(query.getResultList(), kind);
        } catch (SyncopeClientException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("While searching for {}", kind, e);
        }

        return List.of();
    }

    protected StringBuilder buildSelect(final OrderBySupport obs) {
        StringBuilder select = new StringBuilder("SELECT DISTINCT u.any_id");

        obs.items.forEach(item -> select.append(',').append(item.select));
        select.append(" FROM ");

        return select;
    }

    protected void processOBS(
            final SearchSupport svs,
            final OrderBySupport obs,
            final StringBuilder where) {

        Set<String> attrs = obs.items.stream().
                map(item -> item.orderBy.substring(0, item.orderBy.indexOf(' '))).collect(Collectors.toSet());

        obs.views.forEach(searchView -> {
            where.append(',');

            boolean searchViewAddedToWhere = false;
            if (searchView.name().equals(svs.asSearchViewSupport().attr().name())) {
                StringBuilder attrWhere = new StringBuilder();
                StringBuilder nullAttrWhere = new StringBuilder();

                if (svs.nonMandatorySchemas || obs.nonMandatorySchemas) {
                    where.append(" (SELECT * FROM ").append(searchView.name());
                    searchViewAddedToWhere = true;

                    attrs.forEach(field -> {
                        if (attrWhere.length() == 0) {
                            attrWhere.append(" WHERE ");
                        } else {
                            attrWhere.append(" OR ");
                        }
                        attrWhere.append("schema_id='").append(field).append("'");

                        nullAttrWhere.append(" UNION SELECT any_id, ").
                                append("'").
                                append(field).
                                append("' AS schema_id, ").
                                append("null AS booleanvalue, ").
                                append("null AS datevalue, ").
                                append("null AS doublevalue, ").
                                append("null AS longvalue, ").
                                append("null AS stringvalue FROM ").append(svs.field().name()).
                                append(" WHERE ").
                                append("any_id NOT IN (").
                                append("SELECT any_id FROM ").
                                append(svs.asSearchViewSupport().attr().name()).append(' ').append(searchView.alias()).
                                append(" WHERE ").append("schema_id='").append(field).append("')");
                    });
                    where.append(attrWhere).append(nullAttrWhere).append(')');
                }
            }
            if (!searchViewAddedToWhere) {
                where.append(searchView.name());
            }

            where.append(' ').append(searchView.alias());
        });
    }

    protected StringBuilder buildWhere(
            final SearchSupport svs,
            final OrderBySupport obs) {

        StringBuilder where = new StringBuilder(" u");
        processOBS(svs, obs, where);
        where.append(" WHERE ");

        obs.views.forEach(searchView -> where.append("u.any_id=").append(searchView.alias()).append(".any_id AND "));

        obs.items.stream().
                filter(item -> StringUtils.isNotBlank(item.where)).
                forEach(item -> where.append(item.where).append(" AND "));

        return where;
    }

    protected StringBuilder buildOrderBy(final OrderBySupport obs) {
        StringBuilder orderBy = new StringBuilder();

        if (!obs.items.isEmpty()) {
            obs.items.forEach(item -> orderBy.append(item.orderBy).append(','));

            orderBy.insert(0, " ORDER BY ");
            orderBy.deleteCharAt(orderBy.length() - 1);
        }

        return orderBy;
    }

    protected void parseOrderByForPlainSchema(
            final SearchSupport svs,
            final OrderBySupport obs,
            final OrderBySupport.Item item,
            final Sort.Order clause,
            final PlainSchema schema,
            final String fieldName) {

        // keep track of involvement of non-mandatory schemas in the order by clauses
        obs.nonMandatorySchemas = !"true".equals(schema.getMandatoryCondition());

        if (schema.isUniqueConstraint()) {
            obs.views.add(svs.asSearchViewSupport().uniqueAttr());

            item.select = new StringBuilder().
                    append(svs.asSearchViewSupport().uniqueAttr().alias()).append('.').
                    append(key(schema.getType())).
                    append(" AS ").append(fieldName).toString();
            item.where = new StringBuilder().
                    append(svs.asSearchViewSupport().uniqueAttr().alias()).
                    append(".schema_id='").append(fieldName).append("'").toString();
            item.orderBy = fieldName + ' ' + clause.getDirection().name();
        } else {
            obs.views.add(svs.asSearchViewSupport().attr());

            item.select = new StringBuilder().
                    append(svs.asSearchViewSupport().attr().alias()).append('.').append(key(schema.getType())).
                    append(" AS ").append(fieldName).toString();
            item.where = new StringBuilder().
                    append(svs.asSearchViewSupport().attr().alias()).
                    append(".schema_id='").append(fieldName).append("'").toString();
            item.orderBy = fieldName + ' ' + clause.getDirection().name();
        }
    }

    protected void parseOrderByForField(
            final SearchSupport svs,
            final OrderBySupport.Item item,
            final String fieldName,
            final Sort.Order clause) {

        item.select = svs.field().alias() + '.' + fieldName;
        item.where = StringUtils.EMPTY;
        item.orderBy = svs.field().alias() + '.' + fieldName + ' ' + clause.getDirection().name();
    }

    protected void parseOrderByForCustom(
            final SearchSupport svs,
            final Sort.Order clause,
            final OrderBySupport.Item item,
            final OrderBySupport obs) {

        // do nothing by default, meant for subclasses
    }

    protected OrderBySupport parseOrderBy(
            final SearchSupport svs,
            final Stream<Sort.Order> orderBy) {

        AnyUtils anyUtils = anyUtilsFactory.getInstance(svs.anyTypeKind);

        OrderBySupport obs = new OrderBySupport();

        Set<String> orderByUniquePlainSchemas = new HashSet<>();
        Set<String> orderByNonUniquePlainSchemas = new HashSet<>();
        orderBy.forEach(clause -> {
            OrderBySupport.Item item = new OrderBySupport.Item();

            parseOrderByForCustom(svs, clause, item, obs);

            if (item.isEmpty()) {
                if (anyUtils.getField(clause.getProperty()).isPresent()) {
                    String fieldName = clause.getProperty();

                    // Adjust field name to column name
                    if (ArrayUtils.contains(RELATIONSHIP_FIELDS, fieldName)) {
                        fieldName += "_id";
                    }

                    obs.views.add(svs.field());

                    parseOrderByForField(svs, item, fieldName, clause);
                } else {
                    Optional<? extends PlainSchema> schema = plainSchemaDAO.findById(clause.getProperty());
                    if (schema.isPresent()) {
                        if (schema.get().isUniqueConstraint()) {
                            orderByUniquePlainSchemas.add(schema.get().getKey());
                        } else {
                            orderByNonUniquePlainSchemas.add(schema.get().getKey());
                        }
                        if (orderByUniquePlainSchemas.size() > 1 || orderByNonUniquePlainSchemas.size() > 1) {
                            SyncopeClientException invalidSearch =
                                    SyncopeClientException.build(ClientExceptionType.InvalidSearchParameters);
                            invalidSearch.getElements().add("Order by more than one attribute is not allowed; "
                                    + "remove one from " + (orderByUniquePlainSchemas.size() > 1
                                    ? orderByUniquePlainSchemas : orderByNonUniquePlainSchemas));
                            throw invalidSearch;
                        }
                        parseOrderByForPlainSchema(svs, obs, item, clause, schema.get(), clause.getProperty());
                    }
                }
            }

            if (item.isEmpty()) {
                LOG.warn("Cannot build any valid clause from {}", clause);
            } else {
                obs.items.add(item);
            }
        });

        return obs;
    }

    protected void getQueryForCustomConds(
            final SearchCond cond,
            final List<Object> parameters,
            final SearchSupport svs,
            final boolean not,
            final StringBuilder query) {

        // do nothing by default, leave it open for subclasses
    }

    protected void queryOp(
            final StringBuilder query,
            final String op,
            final Pair<StringBuilder, Set<String>> leftInfo,
            final Pair<StringBuilder, Set<String>> rightInfo) {

        String subQuery = leftInfo.getLeft().toString();
        // Add extra parentheses
        subQuery = subQuery.replaceFirst("WHERE ", "WHERE (");
        query.append(subQuery).
                append(' ').append(op).append(" any_id IN ( ").append(rightInfo.getLeft()).append("))");
    }

    protected Pair<StringBuilder, Set<String>> getQuery(
            final SearchCond cond, final List<Object> parameters, final SearchSupport svs) {

        boolean not = cond.getType() == SearchCond.Type.NOT_LEAF;

        StringBuilder query = new StringBuilder();
        Set<String> involvedPlainAttrs = new HashSet<>();

        switch (cond.getType()) {
            case LEAF, NOT_LEAF -> {
                cond.getLeaf(AnyTypeCond.class).
                        filter(leaf -> AnyTypeKind.ANY_OBJECT == svs.anyTypeKind).
                        ifPresent(leaf -> query.append(getQuery(leaf, not, parameters, svs)));

                cond.getLeaf(AuxClassCond.class).
                        ifPresent(leaf -> query.append(getQuery(leaf, not, parameters, svs)));

                cond.getLeaf(RelationshipTypeCond.class).
                        filter(leaf -> AnyTypeKind.GROUP != svs.anyTypeKind).
                        ifPresent(leaf -> query.append(getQuery(leaf, not, parameters, svs)));

                cond.getLeaf(RelationshipCond.class).
                        filter(leaf -> AnyTypeKind.GROUP != svs.anyTypeKind).
                        ifPresent(leaf -> query.append(getQuery(leaf, not, parameters, svs)));

                cond.getLeaf(MembershipCond.class).
                        filter(leaf -> AnyTypeKind.GROUP != svs.anyTypeKind).
                        ifPresent(leaf -> query.append(getQuery(leaf, not, parameters, svs)));

                cond.getLeaf(MemberCond.class).
                        filter(leaf -> AnyTypeKind.GROUP == svs.anyTypeKind).
                        ifPresent(leaf -> query.append(getQuery(leaf, not, parameters, svs)));

                cond.getLeaf(RoleCond.class).
                        filter(leaf -> AnyTypeKind.USER == svs.anyTypeKind).
                        ifPresent(leaf -> query.append(getQuery(leaf, not, parameters, svs)));

                cond.getLeaf(PrivilegeCond.class).
                        filter(leaf -> AnyTypeKind.USER == svs.anyTypeKind).
                        ifPresent(leaf -> query.append(getQuery(leaf, not, parameters, svs)));

                cond.getLeaf(DynRealmCond.class).
                        ifPresent(leaf -> query.append(getQuery(leaf, not, parameters, svs)));

                cond.getLeaf(ResourceCond.class).
                        ifPresent(leaf -> query.append(getQuery(leaf, not, parameters, svs)));

                cond.getLeaf(AnyCond.class).ifPresentOrElse(
                        anyCond -> query.append(getQuery(anyCond, not, parameters, svs)),
                        () -> cond.getLeaf(AttrCond.class).ifPresent(leaf -> {
                            query.append(getQuery(leaf, not, parameters, svs));
                            try {
                                involvedPlainAttrs.add(check(leaf, svs.anyTypeKind).getLeft().getKey());
                            } catch (IllegalArgumentException e) {
                                // ignore
                            }
                        }));

                // allow for additional search conditions
                getQueryForCustomConds(cond, parameters, svs, not, query);
            }

            case AND -> {
                Pair<StringBuilder, Set<String>> leftAndInfo = getQuery(cond.getLeft(), parameters, svs);
                involvedPlainAttrs.addAll(leftAndInfo.getRight());

                Pair<StringBuilder, Set<String>> rigthAndInfo = getQuery(cond.getRight(), parameters, svs);
                involvedPlainAttrs.addAll(rigthAndInfo.getRight());

                queryOp(query, "AND", leftAndInfo, rigthAndInfo);
            }

            case OR -> {
                Pair<StringBuilder, Set<String>> leftOrInfo = getQuery(cond.getLeft(), parameters, svs);
                involvedPlainAttrs.addAll(leftOrInfo.getRight());

                Pair<StringBuilder, Set<String>> rigthOrInfo = getQuery(cond.getRight(), parameters, svs);
                involvedPlainAttrs.addAll(rigthOrInfo.getRight());

                queryOp(query, "OR", leftOrInfo, rigthOrInfo);
            }

            default -> {
            }
        }

        return Pair.of(query, involvedPlainAttrs);
    }

    protected String getQuery(
            final AnyTypeCond cond,
            final boolean not,
            final List<Object> parameters,
            final SearchSupport svs) {

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name()).append(" WHERE type_id");

        if (not) {
            query.append("<>");
        } else {
            query.append('=');
        }

        query.append('?').append(setParameter(parameters, cond.getAnyTypeKey()));

        return query.toString();
    }

    protected String getQuery(
            final AuxClassCond cond,
            final boolean not,
            final List<Object> parameters,
            final SearchSupport svs) {

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name()).append(" WHERE ");

        if (not) {
            query.append("any_id NOT IN (");
        } else {
            query.append("any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(svs.auxClass().name()).
                append(" WHERE anyTypeClass_id=?").
                append(setParameter(parameters, cond.getAuxClass())).
                append(')');

        return query.toString();
    }

    protected String getQuery(
            final RelationshipTypeCond cond,
            final boolean not,
            final List<Object> parameters,
            final SearchSupport svs) {

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name()).append(" WHERE ");

        if (not) {
            query.append("any_id NOT IN (");
        } else {
            query.append("any_id IN (");
        }

        query.append("SELECT any_id ").append("FROM ").
                append(svs.relationship().name()).
                append(" WHERE type=?").append(setParameter(parameters, cond.getRelationshipTypeKey())).
                append(" UNION SELECT right_any_id AS any_id FROM ").
                append(svs.relationship().name()).
                append(" WHERE type=?").append(setParameter(parameters, cond.getRelationshipTypeKey())).
                append(')');

        return query.toString();
    }

    protected String getQuery(
            final RelationshipCond cond,
            final boolean not,
            final List<Object> parameters,
            final SearchSupport svs) {

        Set<String> rightAnyObjects = check(cond);

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name()).append(" WHERE ");

        if (not) {
            query.append("any_id NOT IN (");
        } else {
            query.append("any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(svs.relationship().name()).append(" WHERE ").
                append(rightAnyObjects.stream().
                        map(key -> "right_any_id=?" + setParameter(parameters, key)).
                        collect(Collectors.joining(" OR "))).
                append(')');

        return query.toString();
    }

    protected String getQuery(
            final MembershipCond cond,
            final boolean not,
            final List<Object> parameters,
            final SearchSupport svs) {

        List<String> groupKeys = check(cond);

        String where = groupKeys.stream().
                map(key -> "group_id=?" + setParameter(parameters, key)).
                collect(Collectors.joining(" OR "));

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name()).append(" WHERE (");

        if (not) {
            query.append("any_id NOT IN (");
        } else {
            query.append("any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(svs.membership().name()).append(" WHERE ").
                append(where).
                append(") ");

        if (not) {
            query.append("AND any_id NOT IN (");
        } else {
            query.append("OR any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(svs.dyngroupmembership().name()).append(" WHERE ").
                append(where).
                append("))");

        return query.toString();
    }

    protected String getQuery(
            final RoleCond cond,
            final boolean not,
            final List<Object> parameters,
            final SearchSupport svs) {

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name()).append(" WHERE (");

        if (not) {
            query.append("any_id NOT IN (");
        } else {
            query.append("any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(svs.role().name()).append(" WHERE ").
                append("role_id=?").append(setParameter(parameters, cond.getRole())).
                append(") ");

        if (not) {
            query.append("AND any_id NOT IN (");
        } else {
            query.append("OR any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(SearchSupport.dynrolemembership().name()).append(" WHERE ").
                append("role_id=?").append(setParameter(parameters, cond.getRole())).
                append("))");

        return query.toString();
    }

    protected String getQuery(
            final PrivilegeCond cond,
            final boolean not,
            final List<Object> parameters,
            final SearchSupport svs) {

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name()).append(" WHERE (");

        if (not) {
            query.append("any_id NOT IN (");
        } else {
            query.append("any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(svs.priv().name()).append(" WHERE ").
                append("privilege_id=?").append(setParameter(parameters, cond.getPrivilege())).
                append(") ");

        if (not) {
            query.append("AND any_id NOT IN (");
        } else {
            query.append("OR any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(svs.dynpriv().name()).append(" WHERE ").
                append("privilege_id=?").append(setParameter(parameters, cond.getPrivilege())).
                append("))");

        return query.toString();
    }

    protected String getQuery(
            final DynRealmCond cond,
            final boolean not,
            final List<Object> parameters,
            final SearchSupport svs) {

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name()).append(" WHERE (");

        if (not) {
            query.append("any_id NOT IN (");
        } else {
            query.append("any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(SearchSupport.dynrealmmembership().name()).append(" WHERE ").
                append("dynRealm_id=?").append(setParameter(parameters, cond.getDynRealm())).
                append("))");

        return query.toString();
    }

    protected String getQuery(
            final ResourceCond cond,
            final boolean not,
            final List<Object> parameters,
            final SearchSupport svs) {

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name()).append(" WHERE ");

        if (not) {
            query.append("any_id NOT IN (");
        } else {
            query.append("any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(svs.resource().name()).
                append(" WHERE resource_id=?").
                append(setParameter(parameters, cond.getResource()));

        if (svs.anyTypeKind == AnyTypeKind.USER || svs.anyTypeKind == AnyTypeKind.ANY_OBJECT) {
            query.append(" UNION SELECT DISTINCT any_id FROM ").
                    append(svs.groupResource().name()).
                    append(" WHERE resource_id=?").
                    append(setParameter(parameters, cond.getResource()));
        }

        query.append(')');

        return query.toString();
    }

    protected String getQuery(
            final MemberCond cond,
            final boolean not,
            final List<Object> parameters,
            final SearchSupport svs) {

        Set<String> members = check(cond);

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name()).append(" WHERE ");

        if (not) {
            query.append("any_id NOT IN (");
        } else {
            query.append("any_id IN (");
        }

        query.append("SELECT DISTINCT group_id AS any_id FROM ").
                append(new SearchSupport(AnyTypeKind.USER).membership().name()).append(" WHERE ").
                append(members.stream().
                        map(key -> "any_id=?" + setParameter(parameters, key)).
                        collect(Collectors.joining(" OR "))).
                append(") ");

        if (not) {
            query.append("AND any_id NOT IN (");
        } else {
            query.append("OR any_id IN (");
        }

        query.append("SELECT DISTINCT group_id AS any_id FROM ").
                append(new SearchSupport(AnyTypeKind.ANY_OBJECT).membership().name()).append(" WHERE ").
                append(members.stream().
                        map(key -> "any_id=?" + setParameter(parameters, key)).
                        collect(Collectors.joining(" OR "))).
                append(')');

        return query.toString();
    }

    protected void fillAttrQuery(
            final StringBuilder query,
            final PlainAttrValue attrValue,
            final PlainSchema schema,
            final AttrCond cond,
            final boolean not,
            final List<Object> parameters,
            final SearchSupport svs) {

        // This first branch is required for handling with not conditions given on multivalue fields (SYNCOPE-1419)
        if (not && schema.isMultivalue()
                && !(cond instanceof AnyCond)
                && cond.getType() != AttrCond.Type.ISNULL && cond.getType() != AttrCond.Type.ISNOTNULL) {

            query.append("any_id NOT IN (SELECT DISTINCT any_id FROM ");
            if (schema.isUniqueConstraint()) {
                query.append(svs.asSearchViewSupport().uniqueAttr().name());
            } else {
                query.append(svs.asSearchViewSupport().attr().name());
            }
            query.append(" WHERE schema_id='").append(schema.getKey());
            fillAttrQuery(query, attrValue, schema, cond, false, parameters, svs);
            query.append(')');
        } else {
            // activate ignoreCase only for EQ and LIKE operators
            boolean ignoreCase = AttrCond.Type.ILIKE == cond.getType() || AttrCond.Type.IEQ == cond.getType();

            String column = (cond instanceof AnyCond) ? cond.getSchema() : key(schema.getType());
            if ((schema.getType() == AttrSchemaType.String || schema.getType() == AttrSchemaType.Enum) && ignoreCase) {
                column = "LOWER (" + column + ')';
            }
            if (!(cond instanceof AnyCond)) {
                column = "' AND " + column;
            }

            switch (cond.getType()) {

                case ISNULL ->
                    query.append(column).append(not
                            ? " IS NOT NULL"
                            : " IS NULL");

                case ISNOTNULL ->
                    query.append(column).append(not
                            ? " IS NULL"
                            : " IS NOT NULL");

                case ILIKE, LIKE -> {
                    if (schema.getType() == AttrSchemaType.String || schema.getType() == AttrSchemaType.Enum) {
                        query.append(column);
                        if (not) {
                            query.append(" NOT ");
                        }
                        query.append(" LIKE ");
                        if (ignoreCase) {
                            query.append("LOWER(?").append(setParameter(parameters, cond.getExpression())).append(')');
                        } else {
                            query.append('?').append(setParameter(parameters, cond.getExpression()));
                        }
                        // workaround for Oracle DB adding explicit escaping string, to search 
                        // for literal _ (underscore) (SYNCOPE-1779)
                        if (isOracle()) {
                            query.append(" ESCAPE '\\' ");
                        }
                    } else {
                        if (!(cond instanceof AnyCond)) {
                            query.append("' AND");
                        }
                        query.append(" 1=2");
                        LOG.error("LIKE is only compatible with string or enum schemas");
                    }
                }
                case IEQ, EQ -> {
                    query.append(column);
                    if (not) {
                        query.append("<>");
                    } else {
                        query.append('=');
                    }
                    if ((schema.getType() == AttrSchemaType.String
                            || schema.getType() == AttrSchemaType.Enum) && ignoreCase) {
                        query.append("LOWER(?").append(setParameter(parameters, attrValue.getValue())).append(')');
                    } else {
                        query.append('?').append(setParameter(parameters, attrValue.getValue()));
                    }
                }

                case GE -> {
                    query.append(column);
                    if (not) {
                        query.append('<');
                    } else {
                        query.append(">=");
                    }
                    query.append('?').append(setParameter(parameters, attrValue.getValue()));
                }
                case GT -> {
                    query.append(column);
                    if (not) {
                        query.append("<=");
                    } else {
                        query.append('>');
                    }
                    query.append('?').append(setParameter(parameters, attrValue.getValue()));
                }

                case LE -> {
                    query.append(column);
                    if (not) {
                        query.append('>');
                    } else {
                        query.append("<=");
                    }
                    query.append('?').append(setParameter(parameters, attrValue.getValue()));
                }

                case LT -> {
                    query.append(column);
                    if (not) {
                        query.append(">=");
                    } else {
                        query.append('<');
                    }
                    query.append('?').append(setParameter(parameters, attrValue.getValue()));
                }

                default -> {
                }
            }
        }
    }

    protected String getQuery(
            final AttrCond cond,
            final boolean not,
            final List<Object> parameters,
            final SearchSupport svs) {

        Pair<PlainSchema, PlainAttrValue> checked = check(cond, svs.anyTypeKind);

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ");
        switch (cond.getType()) {
            case ISNOTNULL:
                query.append(checked.getLeft().isUniqueConstraint()
                        ? svs.asSearchViewSupport().uniqueAttr().name()
                        : svs.asSearchViewSupport().attr().name()).
                        append(" WHERE schema_id=").append("'").append(checked.getLeft().getKey()).append("'");
                break;

            case ISNULL:
                query.append(svs.field().name()).
                        append(" WHERE any_id NOT IN ").
                        append('(').
                        append("SELECT DISTINCT any_id FROM ").
                        append(checked.getLeft().isUniqueConstraint()
                                ? svs.asSearchViewSupport().uniqueAttr().name()
                                : svs.asSearchViewSupport().attr().name()).
                        append(" WHERE schema_id=").append("'").append(checked.getLeft().getKey()).append("'").
                        append(')');
                break;

            default:
                if (not && !(cond instanceof AnyCond) && checked.getLeft().isMultivalue()) {
                    query.append(svs.field().name()).append(" WHERE ");
                } else {
                    if (checked.getLeft().isUniqueConstraint()) {
                        query.append(svs.asSearchViewSupport().uniqueAttr().name());
                    } else {
                        query.append(svs.asSearchViewSupport().attr().name());
                    }
                    query.append(" WHERE schema_id='").append(checked.getLeft().getKey());
                }
                fillAttrQuery(query, checked.getRight(), checked.getLeft(), cond, not, parameters, svs);
        }

        return query.toString();
    }

    protected String getQuery(
            final AnyCond cond,
            final boolean not,
            final List<Object> parameters,
            final SearchSupport svs) {

        if (JAXRSService.PARAM_REALM.equals(cond.getSchema())
                && !SyncopeConstants.UUID_PATTERN.matcher(cond.getExpression()).matches()) {

            Realm realm = realmSearchDAO.findByFullPath(cond.getExpression()).
                    orElseThrow(() -> new IllegalArgumentException("Invalid Realm full path: " + cond.getExpression()));
            cond.setExpression(realm.getKey());
        }

        Triple<PlainSchema, PlainAttrValue, AnyCond> checked = check(cond, svs.anyTypeKind);

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name()).append(" WHERE ");

        fillAttrQuery(query, checked.getMiddle(), checked.getLeft(), checked.getRight(), not, parameters, svs);

        return query.toString();
    }
}
