/*
 * Copyright 2006-2007 Sxip Identity Corporation
 */

package org.openid4java.server;

import org.apache.log4j.Logger;
import org.openid4java.yadis.YadisResolver;
import org.openid4java.discovery.Discovery;
import org.openid4java.discovery.DiscoveryException;
import org.openid4java.discovery.DiscoveryInformation;

import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.regex.Pattern;
import java.net.URL;
import java.net.MalformedURLException;

/**
 * @author Marius Scurtescu, Johnny Bufu
 */
public class RealmVerifier
{
    private static Logger _log = Logger.getLogger(RealmVerifier.class);
    private static final boolean DEBUG = _log.isDebugEnabled();

    public static final int OK = 0;
    public static final int DENIED_REALM = 1;
    public static final int MALFORMED_REALM = 2;
    public static final int MALFORMED_RETURN_TO_URL = 3;
    public static final int FRAGMENT_NOT_ALLOWED = 4;
    public static final int PROTOCOL_MISMATCH = 5;
    public static final int PORT_MISMATCH = 6;
    public static final int PATH_MISMATCH = 7;
    public static final int DOMAIN_MISMATCH = 8;
    public static final int RP_DISCOVERY_FAILED = 9;
    public static final int RP_INVALID_ENDPOINT = 10;

    private List _deniedRealmDomains;
    private List _deniedRealmRegExps;

    // yadis resolver used for RP discovery
    private YadisResolver _yadisResolver;

    private boolean _enforceRpId;

    public RealmVerifier()
    {
        _deniedRealmDomains = new ArrayList();

        addDeniedRealmDomain("\\*\\.[^\\.]+");
        addDeniedRealmDomain("\\*\\.[a-z]{2}\\.[a-z]{2}");

        _yadisResolver = new YadisResolver();

        _enforceRpId = true;
    }

    public void addDeniedRealmDomain(String deniedRealmDomain)
    {
        _deniedRealmDomains.add(deniedRealmDomain);

        compileDeniedRealms();
    }

    public List getDeniedRealmDomains()
    {
        return _deniedRealmDomains;
    }

    public void setDeniedRealmDomains(List deniedRealmDomains)
    {
        _deniedRealmDomains = deniedRealmDomains;

        compileDeniedRealms();
    }

    private void compileDeniedRealms()
    {
        _deniedRealmRegExps = new ArrayList(_deniedRealmDomains.size());

        for (int i = 0; i < _deniedRealmDomains.size(); i++)
        {
            String deniedRealm = (String) _deniedRealmDomains.get(i);

            Pattern deniedRealmPattern =
                Pattern.compile(deniedRealm, Pattern.CASE_INSENSITIVE);

            _deniedRealmRegExps.add(deniedRealmPattern);
        }
    }


    public boolean getEnforceRpId()
    {
        return _enforceRpId;
    }

    public void setEnforceRpId(boolean enforceRpId)
    {
        this._enforceRpId = enforceRpId;
    }

    public int validate(String realm, String returnTo)
    {
        return validate(realm, returnTo, _enforceRpId);
    }

    public int validate(String realm, String returnTo, boolean enforceRpId)
    {
        int result;
        // 1. match the return_to against the realm
        result = match(realm, returnTo);

        if (OK != result)
        {
            _log.error("Return URL: " + returnTo +
                       " does not match realm: " + realm);
            return result;
        }

        // 2. match the return_to against RP endpoints discovered from the realm
        result = RP_INVALID_ENDPOINT; // assume there won't get a match
        try
        {
            // replace '*.' with 'www.' in the authority part
            URL realmUrl = new URL(realm);
            if (realmUrl.getAuthority().startsWith("*."))
                realm = realm.replaceFirst("\\*\\.", "www.");

            List endpoints = Discovery.rpDiscovery(realm, _yadisResolver);
            DiscoveryInformation endpoint;
            String endpointUrl;
            Iterator iter = endpoints.iterator();
            while (iter.hasNext())
            {
                endpoint = (DiscoveryInformation) iter.next();
                endpointUrl = endpoint.getIdpEndpoint().toString();

                if (endpoint.getIdpEndpoint().getAuthority().startsWith("*."))
                {
                    _log.warn("Wildcard not allowed in discovered " +
                              "RP endpoints; found: " + endpointUrl);
                    continue;
                }

                if (OK == match(endpointUrl, returnTo))
                {
                    _log.info("Return URL: " + returnTo +
                             " matched discovered RP endpoint: " + endpointUrl);
                    result = OK;
                    break;
                }
            }
        }
        catch (DiscoveryException e)
        {
            _log.error("Discovery failed on realm: " + realm, e);
            result = RP_DISCOVERY_FAILED;
        }
        catch (MalformedURLException e)
        {
            _log.error("Invalid realm URL: " + realm, e);
            result = MALFORMED_REALM;
        }

        // return the result
        if (enforceRpId)
        {
            return result;
        }
        else
        {
            if (OK != result)
                _log.warn("Failed to validate return URL: " + returnTo +
                    "against endpoints discovered from the RP's realm; " +
                    "not enforced, returning OK; error code: " + result);
            return OK;
        }
    }

    public int match(String realm, String returnTo)
    {
        if (DEBUG) _log.debug("Verifying realm: " + realm +
                              " on return URL: " + returnTo);

        URL realmUrl;
        try
        {
            realmUrl = new URL(realm);
        }
        catch (MalformedURLException e)
        {
            _log.error("Invalid realm URL: " + realm, e);
            return MALFORMED_REALM;
        }

        String realmDomain = realmUrl.getHost();

        if (isDeniedRealmDomain(realmDomain))
        {
            _log.warn("Blacklisted realm domain: " + realmDomain);
            return DENIED_REALM;
        }

        URL returnToUrl;
        try
        {
            returnToUrl = new URL(returnTo);
        }
        catch (MalformedURLException e)
        {
            _log.error("Invalid return URL: " + returnTo);
            return MALFORMED_RETURN_TO_URL;
        }

        if (realmUrl.getRef() != null)
        {
            if (DEBUG) _log.debug("Realm verification failed: " +
                                  "URL fragments are not allowed.");
            return FRAGMENT_NOT_ALLOWED;
        }

        if (!realmUrl.getProtocol().equalsIgnoreCase(returnToUrl.getProtocol()))
        {
            if (DEBUG) _log.debug("Realm verification failed: " +
                                  "protocol mismatch.");
            return PROTOCOL_MISMATCH;
        }

        if (!domainMatch(realmDomain, returnToUrl.getHost()))
        {
            if (DEBUG) _log.debug("Realm verification failed: " +
                                  "domain mismatch.");
            return DOMAIN_MISMATCH;
        }

        if (!portMatch(realmUrl, returnToUrl))
        {
            if (DEBUG) _log.debug("Realm verification failed: " +
                                  "port mismatch.");
            return PORT_MISMATCH;
        }

        if (!pathMatch(realmUrl, returnToUrl))
        {
            if (DEBUG) _log.debug("Realm verification failed: " +
                                  "path mismatch.");
            return PATH_MISMATCH;
        }

        _log.info("Return URL: " + returnTo + "matches realm: " + realm);

        return OK;
    }

    private boolean isDeniedRealmDomain(String realmDomain)
    {
        for (int i = 0; i < _deniedRealmRegExps.size(); i++)
        {
            Pattern realmPattern = (Pattern) _deniedRealmRegExps.get(i);

            if (realmPattern.matcher(realmDomain).matches())
                return true;
        }

        return false;
    }

    private boolean portMatch(URL realmUrl, URL returnToUrl)
    {
        int realmPort = realmUrl.getPort();
        int returnToPort  = returnToUrl.getPort();

        if (realmPort == -1)
            realmPort = realmUrl.getDefaultPort();

        if (returnToPort == -1)
            returnToPort = returnToUrl.getDefaultPort();

        return realmPort == returnToPort;
    }

    /**
     * Does the URL's path equal to or a sub-directory of the realm's path.
     *
     * @param realmUrl
     * @param returnToUrl
     * @return If equals or a sub-direcotory return true.
     */
    private boolean pathMatch(URL realmUrl, URL returnToUrl)
    {
        String realmPath = realmUrl.getPath();
        String returnToPath  = returnToUrl.getPath();

        if (!realmPath.endsWith("/"))
            realmPath += "/";

        if (!returnToPath.endsWith("/"))
            returnToPath += "/";

        // return realmPath.startsWith(returnToPath);
        return returnToPath.startsWith(realmPath);
    }

    private boolean domainMatch(String realmDomain, String returnToDomain)
    {
        if (realmDomain.startsWith("*."))
        {
            realmDomain = realmDomain.substring(1).toLowerCase();
            returnToDomain  = "." + returnToDomain.toLowerCase();

            return returnToDomain.endsWith(realmDomain);
        }
        else
            return realmDomain.equalsIgnoreCase(returnToDomain);
    }
}
