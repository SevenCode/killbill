/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.subscription.api.svcs;

import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.billing.ErrorCode;
import com.ning.billing.catalog.api.Catalog;
import com.ning.billing.catalog.api.CatalogApiException;
import com.ning.billing.catalog.api.CatalogService;
import com.ning.billing.catalog.api.Plan;
import com.ning.billing.catalog.api.PlanPhase;
import com.ning.billing.catalog.api.PlanPhaseSpecifier;
import com.ning.billing.catalog.api.PriceListSet;
import com.ning.billing.catalog.api.ProductCategory;
import com.ning.billing.clock.Clock;
import com.ning.billing.clock.DefaultClock;
import com.ning.billing.subscription.api.SubscriptionApiBase;
import com.ning.billing.subscription.api.user.DefaultEffectiveSubscriptionEvent;
import com.ning.billing.subscription.api.user.DefaultSubscriptionApiService;
import com.ning.billing.subscription.api.user.DefaultSubscriptionStatusDryRun;
import com.ning.billing.subscription.api.user.Subscription;
import com.ning.billing.subscription.api.user.SubscriptionBuilder;
import com.ning.billing.subscription.api.user.SubscriptionBundle;
import com.ning.billing.subscription.api.user.SubscriptionBundleData;
import com.ning.billing.subscription.api.user.SubscriptionData;
import com.ning.billing.subscription.api.user.SubscriptionState;
import com.ning.billing.subscription.api.user.SubscriptionStatusDryRun;
import com.ning.billing.subscription.api.user.SubscriptionStatusDryRun.DryRunChangeReason;
import com.ning.billing.subscription.api.user.SubscriptionTransition;
import com.ning.billing.subscription.api.user.SubscriptionTransitionData;
import com.ning.billing.subscription.api.user.SubscriptionUserApiException;
import com.ning.billing.subscription.engine.addon.AddonUtils;
import com.ning.billing.subscription.engine.dao.SubscriptionDao;
import com.ning.billing.subscription.exceptions.SubscriptionError;
import com.ning.billing.util.callcontext.InternalCallContext;
import com.ning.billing.util.callcontext.InternalTenantContext;
import com.ning.billing.util.events.EffectiveSubscriptionInternalEvent;
import com.ning.billing.util.svcapi.subscription.SubscriptionInternalApi;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;

public class DefaultSubscriptionInternalApi extends SubscriptionApiBase implements SubscriptionInternalApi {

    private final Logger log = LoggerFactory.getLogger(DefaultSubscriptionInternalApi.class);

    private final AddonUtils addonUtils;

    @Inject
    public DefaultSubscriptionInternalApi(final SubscriptionDao dao,
                                          final DefaultSubscriptionApiService apiService,
                                          final Clock clock,
                                          final CatalogService catalogService,
                                          final AddonUtils addonUtils) {
        super(dao, apiService, clock, catalogService);
        this.addonUtils = addonUtils;
    }

    @Override
    public Subscription createSubscription(final UUID bundleId, final PlanPhaseSpecifier spec, final DateTime requestedDateWithMs, final InternalCallContext context) throws SubscriptionUserApiException {
        try {
            final String realPriceList = (spec.getPriceListName() == null) ? PriceListSet.DEFAULT_PRICELIST_NAME : spec.getPriceListName();
            final DateTime now = clock.getUTCNow();
            final DateTime requestedDate = (requestedDateWithMs != null) ? DefaultClock.truncateMs(requestedDateWithMs) : now;
            if (requestedDate.isAfter(now)) {
                throw new SubscriptionUserApiException(ErrorCode.SUB_INVALID_REQUESTED_DATE, now.toString(), requestedDate.toString());
            }
            final DateTime effectiveDate = requestedDate;

            final Catalog catalog = catalogService.getFullCatalog();
            final Plan plan = catalog.findPlan(spec.getProductName(), spec.getBillingPeriod(), realPriceList, requestedDate);

            final PlanPhase phase = plan.getAllPhases()[0];
            if (phase == null) {
                throw new SubscriptionError(String.format("No initial PlanPhase for Product %s, term %s and set %s does not exist in the catalog",
                                                          spec.getProductName(), spec.getBillingPeriod().toString(), realPriceList));
            }

            final SubscriptionBundle bundle = dao.getSubscriptionBundleFromId(bundleId, context);
            if (bundle == null) {
                throw new SubscriptionUserApiException(ErrorCode.SUB_CREATE_NO_BUNDLE, bundleId);
            }

            DateTime bundleStartDate = null;
            final SubscriptionData baseSubscription = (SubscriptionData) dao.getBaseSubscription(bundleId, context);
            switch (plan.getProduct().getCategory()) {
                case BASE:
                    if (baseSubscription != null) {
                        if (baseSubscription.getState() == SubscriptionState.ACTIVE) {
                            throw new SubscriptionUserApiException(ErrorCode.SUB_CREATE_BP_EXISTS, bundleId);
                        } else {
                            // If we do create on an existing CANCELLED BP, this is equivalent to call recreate on that Subscription.
                            final Subscription recreatedSubscriptionForApiUse = createSubscriptionForApiUse(baseSubscription);
                            recreatedSubscriptionForApiUse.recreate(spec, requestedDate, context.toCallContext());
                            return recreatedSubscriptionForApiUse;
                        }
                    }
                    bundleStartDate = requestedDate;
                    break;
                case ADD_ON:
                    if (baseSubscription == null) {
                        throw new SubscriptionUserApiException(ErrorCode.SUB_CREATE_NO_BP, bundleId);
                    }
                    if (effectiveDate.isBefore(baseSubscription.getStartDate())) {
                        throw new SubscriptionUserApiException(ErrorCode.SUB_INVALID_REQUESTED_DATE, effectiveDate.toString(), baseSubscription.getStartDate().toString());
                    }
                    addonUtils.checkAddonCreationRights(baseSubscription, plan);
                    bundleStartDate = baseSubscription.getStartDate();
                    break;
                case STANDALONE:
                    if (baseSubscription != null) {
                        throw new SubscriptionUserApiException(ErrorCode.SUB_CREATE_BP_EXISTS, bundleId);
                    }
                    // Not really but we don't care, there is no alignment for STANDALONE subscriptions
                    bundleStartDate = requestedDate;
                    break;
                default:
                    throw new SubscriptionError(String.format("Can't create subscription of type %s",
                                                              plan.getProduct().getCategory().toString()));
            }

            return apiService.createPlan(new SubscriptionBuilder()
                                                 .setId(UUID.randomUUID())
                                                 .setBundleId(bundleId)
                                                 .setCategory(plan.getProduct().getCategory())
                                                 .setBundleStartDate(bundleStartDate)
                                                 .setAlignStartDate(effectiveDate),
                                         plan, spec.getPhaseType(), realPriceList, requestedDate, effectiveDate, now, context.toCallContext());
        } catch (CatalogApiException e) {
            throw new SubscriptionUserApiException(e);
        }
    }

    @Override
    public SubscriptionBundle createBundleForAccount(final UUID accountId, final String bundleName, final InternalCallContext context) throws SubscriptionUserApiException {
        final SubscriptionBundleData bundle = new SubscriptionBundleData(bundleName, accountId, clock.getUTCNow());
        return dao.createSubscriptionBundle(bundle, context);
    }

    @Override
    public SubscriptionBundle getBundleForAccountAndKey(final UUID accountId, final String bundleKey, final InternalTenantContext context) throws SubscriptionUserApiException {
        final SubscriptionBundle result = dao.getSubscriptionBundleFromAccountAndKey(accountId, bundleKey, context);
        if (result == null) {
            throw new SubscriptionUserApiException(ErrorCode.SUB_GET_INVALID_BUNDLE_KEY, bundleKey);
        }
        return result;
    }

    @Override
    public List<SubscriptionBundle> getBundlesForAccount(final UUID accountId, final InternalTenantContext context) {
        return dao.getSubscriptionBundleForAccount(accountId, context);
    }

    @Override
    public List<Subscription> getSubscriptionsForBundle(UUID bundleId,
                                                        InternalTenantContext context) {
        final List<Subscription> internalSubscriptions = dao.getSubscriptions(bundleId, context);
        return createSubscriptionsForApiUse(internalSubscriptions);
    }

    @Override
    public Subscription getBaseSubscription(UUID bundleId,
                                            InternalTenantContext context) throws SubscriptionUserApiException {
        final Subscription result = dao.getBaseSubscription(bundleId, context);
        if (result == null) {
            throw new SubscriptionUserApiException(ErrorCode.SUB_GET_NO_SUCH_BASE_SUBSCRIPTION, bundleId);
        }
        return createSubscriptionForApiUse(result);
    }

    @Override

    public Subscription getSubscriptionFromId(UUID id,
                                              InternalTenantContext context) throws SubscriptionUserApiException {
        final Subscription result = dao.getSubscriptionFromId(id, context);
        if (result == null) {
            throw new SubscriptionUserApiException(ErrorCode.SUB_INVALID_SUBSCRIPTION_ID, id);
        }
        return createSubscriptionForApiUse(result);
    }

    @Override
    public SubscriptionBundle getBundleFromId(final UUID id, final InternalTenantContext context) throws SubscriptionUserApiException {
        final SubscriptionBundle result = dao.getSubscriptionBundleFromId(id, context);
        if (result == null) {
            throw new SubscriptionUserApiException(ErrorCode.SUB_GET_INVALID_BUNDLE_ID, id.toString());
        }
        return result;
    }

    @Override
    public UUID getAccountIdFromSubscriptionId(final UUID subscriptionId, final InternalTenantContext context) throws SubscriptionUserApiException {
        return dao.getAccountIdFromSubscriptionId(subscriptionId, context);
    }

    @Override
    public void setChargedThroughDate(UUID subscriptionId,
                                      DateTime chargedThruDate, InternalCallContext context) {
        final SubscriptionData subscription = (SubscriptionData) dao.getSubscriptionFromId(subscriptionId, context);
        final SubscriptionBuilder builder = new SubscriptionBuilder(subscription)
                .setChargedThroughDate(chargedThruDate)
                .setPaidThroughDate(subscription.getPaidThroughDate());

        dao.updateChargedThroughDate(new SubscriptionData(builder), context);
    }

    @Override
    public List<EffectiveSubscriptionInternalEvent> getAllTransitions(final Subscription subscription, final InternalTenantContext context) {
        final List<SubscriptionTransition> transitions = ((SubscriptionData) subscription).getAllTransitions();
        return convertEffectiveSubscriptionInternalEventFromSubscriptionTransitions(subscription, context, transitions);
    }

    @Override
    public List<EffectiveSubscriptionInternalEvent> getBillingTransitions(final Subscription subscription, final InternalTenantContext context) {
        final List<SubscriptionTransition> transitions = ((SubscriptionData) subscription).getBillingTransitions();
        return convertEffectiveSubscriptionInternalEventFromSubscriptionTransitions(subscription, context, transitions);
    }

    @Override
    public DateTime getNextBillingDate(final UUID accountId, final InternalTenantContext context) {
        final List<SubscriptionBundle> bundles = getBundlesForAccount(accountId, context);
        DateTime result = null;
        for (final SubscriptionBundle bundle : bundles) {
            final List<Subscription> subscriptions = getSubscriptionsForBundle(bundle.getId(), context);
            for (final Subscription subscription : subscriptions) {
                final DateTime chargedThruDate = subscription.getChargedThroughDate();
                if (result == null ||
                    (chargedThruDate != null && chargedThruDate.isBefore(result))) {
                    result = subscription.getChargedThroughDate();
                }
            }
        }
        return result;
    }

    @Override
    public List<SubscriptionStatusDryRun> getDryRunChangePlanStatus(final UUID subscriptionId, @Nullable final String baseProductName, final DateTime requestedDate, final InternalTenantContext context) throws SubscriptionUserApiException {
        final Subscription subscription = dao.getSubscriptionFromId(subscriptionId, context);
        if (subscription == null) {
            throw new SubscriptionUserApiException(ErrorCode.SUB_INVALID_SUBSCRIPTION_ID, subscriptionId);
        }
        if (subscription.getCategory() != ProductCategory.BASE) {
            throw new SubscriptionUserApiException(ErrorCode.SUB_CHANGE_DRY_RUN_NOT_BP);
        }

        final List<SubscriptionStatusDryRun> result = new LinkedList<SubscriptionStatusDryRun>();

        final List<Subscription> bundleSubscriptions = dao.getSubscriptions(subscription.getBundleId(), context);
        for (final Subscription cur : bundleSubscriptions) {
            if (cur.getId().equals(subscriptionId)) {
                continue;
            }

            // If ADDON is cancelled, skip
            if (cur.getState() == SubscriptionState.CANCELLED) {
                continue;
            }

            final DryRunChangeReason reason;
            // If baseProductName is null, it's a cancellation dry-run. In this case, return all addons, so they are cancelled
            if (baseProductName != null && addonUtils.isAddonIncludedFromProdName(baseProductName, requestedDate, cur.getCurrentPlan())) {
                reason = DryRunChangeReason.AO_INCLUDED_IN_NEW_PLAN;
            } else if (baseProductName != null && addonUtils.isAddonAvailableFromProdName(baseProductName, requestedDate, cur.getCurrentPlan())) {
                reason = DryRunChangeReason.AO_AVAILABLE_IN_NEW_PLAN;
            } else {
                reason = DryRunChangeReason.AO_NOT_AVAILABLE_IN_NEW_PLAN;
            }
            final SubscriptionStatusDryRun status = new DefaultSubscriptionStatusDryRun(cur.getId(),
                                                                                        cur.getCurrentPlan().getProduct().getName(),
                                                                                        cur.getCurrentPhase().getPhaseType(),
                                                                                        cur.getCurrentPlan().getBillingPeriod(),
                                                                                        cur.getCurrentPriceList().getName(), reason);
            result.add(status);
        }
        return result;
    }

    private List<EffectiveSubscriptionInternalEvent> convertEffectiveSubscriptionInternalEventFromSubscriptionTransitions(final Subscription subscription,
                                                                                                                          final InternalTenantContext context, final List<SubscriptionTransition> transitions) {
        return ImmutableList.<EffectiveSubscriptionInternalEvent>copyOf(Collections2.transform(transitions, new Function<SubscriptionTransition, EffectiveSubscriptionInternalEvent>() {
            @Override
            @Nullable
            public EffectiveSubscriptionInternalEvent apply(@Nullable SubscriptionTransition input) {
                return new DefaultEffectiveSubscriptionEvent((SubscriptionTransitionData) input, ((SubscriptionData) subscription).getAlignStartDate(), null, context.getAccountRecordId(), context.getTenantRecordId());
            }
        }));
    }
}