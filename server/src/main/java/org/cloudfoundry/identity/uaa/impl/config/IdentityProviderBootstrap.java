/*******************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2016] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.impl.config;


import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.cloudfoundry.identity.uaa.audit.event.EntityDeletedEvent;
import org.cloudfoundry.identity.uaa.constants.OriginKeys;
import org.cloudfoundry.identity.uaa.provider.AbstractIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.provider.AbstractXOAuthIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.provider.IdentityProvider;
import org.cloudfoundry.identity.uaa.provider.IdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.provider.KeystoneIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.provider.LdapIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.provider.LockoutPolicy;
import org.cloudfoundry.identity.uaa.provider.OIDCIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.provider.PasswordPolicy;
import org.cloudfoundry.identity.uaa.provider.RawXOAuthIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.provider.SamlIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.provider.UaaIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.provider.saml.BootstrapSamlIdentityProviderConfigurator;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.util.LdapUtils;
import org.cloudfoundry.identity.uaa.util.UaaMapUtils;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.dao.EmptyResultDataAccessException;

import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static org.cloudfoundry.identity.uaa.authentication.SystemAuthentication.SYSTEM_AUTHENTICATION;
import static org.cloudfoundry.identity.uaa.constants.OriginKeys.UAA;
import static org.cloudfoundry.identity.uaa.provider.LdapIdentityProviderDefinition.LDAP_PROPERTY_NAMES;
import static org.cloudfoundry.identity.uaa.provider.LdapIdentityProviderDefinition.LDAP_PROPERTY_TYPES;

public class IdentityProviderBootstrap
    implements InitializingBean, ApplicationListener<ContextRefreshedEvent>, ApplicationEventPublisherAware {
    private static Log logger = LogFactory.getLog(IdentityProviderBootstrap.class);

    private IdentityProviderProvisioning provisioning;
    private List<IdentityProvider> providers = new LinkedList<>();
    private BootstrapSamlIdentityProviderConfigurator configurator;
    private Map<String, AbstractXOAuthIdentityProviderDefinition> oauthIdpDefintions;
    private Map<String, Object> ldapConfig;
    private Map<String, Object> keystoneConfig;
    private Environment environment;
    private PasswordPolicy defaultPasswordPolicy;
    private LockoutPolicy defaultLockoutPolicy;
    private boolean disableInternalUserManagement;

    private List<String> originsToDelete = null;
    private ApplicationEventPublisher publisher;

    public IdentityProviderBootstrap(IdentityProviderProvisioning provisioning, Environment environment) {
        if (provisioning==null) {
            throw new NullPointerException("Constructor argument can't be null.");
        }
        this.provisioning = provisioning;
        this.environment = environment;

    }

    private void addOauthProviders() {
        if (oauthIdpDefintions == null) {
            return;
        }
        for (Map.Entry<String, AbstractXOAuthIdentityProviderDefinition> definition : oauthIdpDefintions.entrySet()) {
            validateDuplicateAlias(definition.getKey());
            IdentityProvider provider = new IdentityProvider();
            if (RawXOAuthIdentityProviderDefinition.class.isAssignableFrom(definition.getValue().getClass())) {
                provider.setType(OriginKeys.OAUTH20);
            } else if(OIDCIdentityProviderDefinition.class.isAssignableFrom(definition.getValue().getClass())) {
                provider.setType(OriginKeys.OIDC10);
            } else {
                throw new IllegalArgumentException("Unknown provider type.");
            }
            provider.setOriginKey(definition.getKey());
            provider.setName("UAA Oauth Identity Provider["+provider.getOriginKey()+"]");
            provider.setActive(true);
            try {
                provider.setConfig(definition.getValue());
            } catch (JsonUtils.JsonUtilException x) {
                throw new RuntimeException("Non serializable Oauth config");
            }
            providers.add(provider);
        }
    }

    public void validateDuplicateAlias(String originKey) {
        for (IdentityProvider provider: providers) {
            if (provider.getOriginKey().equals(originKey)) {
                throw new IllegalArgumentException("Provider alias " + originKey + " is not unique.");
            }
        }
    }

    public void setSamlProviders(BootstrapSamlIdentityProviderConfigurator configurator) {
        this.configurator = configurator;
    }
    protected void addSamlProviders() {
        if (configurator==null) {
            return;
        }
        for (SamlIdentityProviderDefinition def : configurator.getIdentityProviderDefinitions()) {
            validateDuplicateAlias(def.getIdpEntityAlias());
            IdentityProvider provider = new IdentityProvider();
            provider.setType(OriginKeys.SAML);
            provider.setOriginKey(def.getIdpEntityAlias());
            provider.setName("UAA SAML Identity Provider["+provider.getOriginKey()+"]");
            provider.setActive(true);
            try {
                provider.setConfig(def);
            } catch (JsonUtils.JsonUtilException x) {
                throw new RuntimeException("Non serializable SAML config");
            }
            providers.add(provider);
        }
    }

    public void setLdapConfig(HashMap<String, Object> ldapConfig) {
        this.ldapConfig = ldapConfig;
    }

    protected void addLdapProvider() {
        boolean ldapProfile = Arrays.asList(environment.getActiveProfiles()).contains(OriginKeys.LDAP);
        //the LDAP provider has to be there
        //and we activate, deactivate based on the `ldap` profile presence
        IdentityProvider provider = new IdentityProvider();
        provider.setActive(ldapProfile);
        provider.setOriginKey(OriginKeys.LDAP);
        provider.setType(OriginKeys.LDAP);
        provider.setName("UAA LDAP Provider");
        Map<String,Object> ldap = new HashMap<>();
        ldap.put(LdapIdentityProviderDefinition.LDAP, ldapConfig);
        LdapIdentityProviderDefinition json = getLdapConfigAsDefinition(ldap);
        provider.setConfig(json);
        provider.setActive(ldapProfile && json.isConfigured());
        providers.add(provider);
    }



    protected LdapIdentityProviderDefinition getLdapConfigAsDefinition(Map<String, Object> ldapConfig) {
        ldapConfig = UaaMapUtils.flatten(ldapConfig);
        populateLdapEnvironment(ldapConfig);
        if (ldapConfig.isEmpty()) {
            return new LdapIdentityProviderDefinition();
        }
        return LdapUtils.fromConfig(ldapConfig);
    }

    protected void populateLdapEnvironment(Map<String, Object> ldapConfig) {
        //this method reads the environment and overwrites values (needed by LdapMockMvcTests that overrides properties through env)
        AbstractEnvironment env = (AbstractEnvironment)environment;
        //these are our known complex data structures in the properties
        for (String property : LDAP_PROPERTY_NAMES) {
            if (env.containsProperty(property) && LDAP_PROPERTY_TYPES.get(property)!=null) {
                ldapConfig.put(property, env.getProperty(property, LDAP_PROPERTY_TYPES.get(property)));
            }
        }

        //but we can also have string properties like ldap.attributeMappings.user.attribute.mapToAttributeName=mapFromAttributeName
        Map<String,Object> stringProperties = UaaMapUtils.getPropertiesStartingWith(env, "ldap.");
        for (Map.Entry<String, Object> entry : stringProperties.entrySet()) {
            if (!LDAP_PROPERTY_NAMES.contains(entry.getKey())) {
                ldapConfig.put(entry.getKey(), entry.getValue());
            }
        }
    }

    public void setKeystoneConfig(HashMap<String, Object> keystoneConfig) {
        this.keystoneConfig = keystoneConfig;
    }

    protected AbstractIdentityProviderDefinition getKeystoneDefinition(Map<String, Object> config) {
        return new KeystoneIdentityProviderDefinition(config);
    }

    protected void addKeystoneProvider() {
        boolean keystoneProfile = Arrays.asList(environment.getActiveProfiles()).contains(OriginKeys.KEYSTONE);
        if (keystoneConfig != null || keystoneProfile) {
            boolean active = keystoneProfile && keystoneConfig!=null;
            IdentityProvider provider = new IdentityProvider();
            provider.setOriginKey(OriginKeys.KEYSTONE);
            provider.setType(OriginKeys.KEYSTONE);
            provider.setName("UAA Keystone Provider");
            provider.setActive(active);
            provider.setConfig(getKeystoneDefinition(keystoneConfig));
            providers.add(provider);
        }
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.publisher = applicationEventPublisher;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        deleteIdentityProviders(IdentityZone.getUaa().getId());
    }

    @Override
    public void afterPropertiesSet() throws Exception {

        providers.clear();
        addLdapProvider();
        addSamlProviders();
        addOauthProviders();
        addKeystoneProvider();

        String zoneId = IdentityZone.getUaa().getId();

        for (IdentityProvider provider: providers) {
            if (getOriginsToDelete().contains(provider.getOriginKey())) {
                //dont process origins slated for deletion
                continue;
            }
            IdentityProvider existing = null;
            try {
                existing = provisioning.retrieveByOrigin(provider.getOriginKey(), zoneId);
            }catch (EmptyResultDataAccessException x){
            }
            provider.setIdentityZoneId(zoneId);
            if (existing==null) {
                provisioning.create(provider, zoneId);
            } else {
                provider.setId(existing.getId());
                provider.setCreated(existing.getCreated());
                provider.setVersion(existing.getVersion());
                provider.setLastModified(new Date(System.currentTimeMillis()));
                provisioning.update(provider, zoneId);
            }
        }
        updateDefaultZoneUaaIDP();
    }

    private void deleteIdentityProviders(String zoneId) {
        for (String origin : getOriginsToDelete()) {
            if (!UAA.equals(origin) && !OriginKeys.LDAP.equals(origin)) {
                try {
                    logger.debug("Attempting to deactivating identity provider:"+origin);
                    IdentityProvider provider = provisioning.retrieveByOrigin(origin, zoneId);
                    //delete provider
                    EntityDeletedEvent<IdentityProvider> event = new EntityDeletedEvent<>(provider, SYSTEM_AUTHENTICATION);
                    if (this.publisher!=null) {
                        publisher.publishEvent(event);
                        logger.debug("Identity provider deactivated:"+origin);
                    } else {
                        logger.warn(
                            String.format("Unable to delete identity provider with origin '%s', no application publisher",
                                          origin)
                        );
                    }

                } catch (EmptyResultDataAccessException x) {}
            }
        }
    }

    protected void updateDefaultZoneUaaIDP() throws JSONException {
        String zoneId = IdentityZone.getUaa().getId();
        IdentityProvider internalIDP = provisioning.retrieveByOrigin(UAA, IdentityZone.getUaa().getId());
        UaaIdentityProviderDefinition identityProviderDefinition = new UaaIdentityProviderDefinition(defaultPasswordPolicy, defaultLockoutPolicy, disableInternalUserManagement);
        internalIDP.setConfig(identityProviderDefinition);
        String disableInternalAuth = environment.getProperty("disableInternalAuth");
        internalIDP.setActive(!getBooleanValue(disableInternalAuth, false));
        provisioning.update(internalIDP, zoneId);
    }

    protected boolean getBooleanValue(String s, boolean defaultValue) {
        if (s != null) {
            return Boolean.valueOf(s);
        } else {
            return defaultValue;
        }
    }

    private boolean isAmongProviders(String originKey, String type) {
        for (IdentityProvider provider: providers) {
            if (provider.getOriginKey().equals(originKey) && provider.getType().equals(type)) {
                return true;
            }
        }
        return false;
    }

    public void setDefaultPasswordPolicy(PasswordPolicy defaultPasswordPolicy) {
        this.defaultPasswordPolicy = defaultPasswordPolicy;
    }

    public void setDefaultLockoutPolicy(LockoutPolicy defaultLockoutPolicy) {
        this.defaultLockoutPolicy = defaultLockoutPolicy;
    }

    public boolean isDisableInternalUserManagement() {
        return disableInternalUserManagement;
    }

    public void setDisableInternalUserManagement(boolean disableInternalUserManagement) {
        this.disableInternalUserManagement = disableInternalUserManagement;
    }

    public void setOauthIdpDefinitions(Map<String, AbstractXOAuthIdentityProviderDefinition> oauthIdpDefintions) {
        this.oauthIdpDefintions = oauthIdpDefintions;
    }

    public void setOriginsToDelete(List<String> originsToDelete) {
        this.originsToDelete = originsToDelete;
    }

    public List<String> getOriginsToDelete() {
        return ofNullable(originsToDelete).orElse(emptyList());
    }
}
