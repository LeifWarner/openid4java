/*
 * Copyright 2006-2007 Sxip Identity Corporation
 */

package net.openid.message.ax;

import net.openid.message.MessageException;
import net.openid.message.Parameter;
import net.openid.message.ParameterList;

import java.net.URL;
import java.net.MalformedURLException;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * Implements the extension for Attribute Exchange fetch requests.
 *
 * @see AxMessage Message
 * @author Marius Scurtescu, Johnny Bufu
 */
public class FetchRequest extends AxMessage
{
    /**
     * Constructs a Fetch Request with an empty parameter list.
     */
    protected FetchRequest()
    {
    }

    /**
     * Constructs a Fetch Request with an empty parameter list.
     */
    public static FetchRequest createFetchRequest()
    {
        return new FetchRequest();
    }

    /**
     * Constructs a FetchRequest from a parameter list.
     * <p>
     * The parameter list can be extracted from a received message with the
     * getExtensionParams method of the Message class, and MUST NOT contain
     * the "openid.<alias>." prefix.
     */
    protected FetchRequest(ParameterList params)
    {
        _parameters = params;

    }

    /**
     * Constructs a FetchRequest from a parameter list.
     * <p>
     * The parameter list can be extracted from a received message with the
     * getExtensionParams method of the Message class, and MUST NOT contain
     * the "openid.<alias>." prefix.
     */
    public static FetchRequest createFetchRequest(ParameterList params)
            throws MessageException
    {
        FetchRequest req = new FetchRequest(params);

        if (! req.isValid())
            throw new MessageException("Invalid parameters for a fetch request");

        return req;
    }

    /**
     * Adds an attribute to the fetch request.
     *
     * @param       id          The identifier that will be associated with the
     *                          attribute name URI
     * @param       attrName    The attribute name URI
     * @param       required    Marks the attribute as 'required';
     *                          'if_available' otherwise.
     */
    public void addAttribute(String id, String attrName, boolean required)
    {
        _parameters.set(new Parameter("fetch." + id, attrName));

        String level = required ? "required" : "if_available";

        Parameter param = _parameters.getParameter(level);
        Parameter newParam;

        if (param == null)
        {
            newParam = new Parameter(level, multivalEncode(id));
        }
        else
        {
            newParam = new Parameter(level,
                    param.getValue() + "," + multivalEncode(id));
            _parameters.removeParameters(level);
        }

        _parameters.set(newParam);
    }

    /**
     * Sets the optional 'update_url' parameter where the IdP can later re-post
     * fetch-response updates to the values of the requested attributes.
     *
     * @param       updateUrl   The URL where the RP accepts later updates
     *                          to the requested attributes.
     */
    public void setUpdateUrl(String updateUrl) throws MessageException
    {
        try
        {
            new URL(updateUrl);
        } catch (MalformedURLException e)
        {
            throw new MessageException("Invalid update_url: " + updateUrl);
        }

        _parameters.set(new Parameter("update_url", updateUrl));
    }

    /**
     * Gets the optional 'update_url' parameter if available, or null otherwise.
     */
    public String getUpdateUrl()
    {
        return _parameters.hasParameter("update_url") ?
                _parameters.getParameterValue("update_url") : null;
    }

    /**
     * Returns a map of attribute IDs -> attribute name URIs.
     *
     * @param       required    If set to true the list of 'required' attributes
     *                          is returned, otherwise the list of 'if_available'
     *                          attributes.
     */
    public Map getAttributes(boolean required)
    {
        HashMap reqAttrs = new LinkedHashMap();

        String level = required ? "required" : "if_available";

        Parameter param = _parameters.getParameter(level);
        if (param != null)
        {
            String[] values = param.getValue().split(",");
            for (int i = 0; i < values.length; i++)
            {
                String attrId = multivalDecode(values[i]);
                reqAttrs.put(attrId,
                        _parameters.getParameterValue("fetch." + attrId));
            }
        }

        return reqAttrs;
    }

    /**
     * Checks the validity of the extension.
     * <p>
     * Used when constructing a extension from a parameter list.
     *
     * @return      True if the extension is valid, false otherwise.
     */
    public boolean isValid()
    {
        if ( ! _parameters.hasParameter("required") &&
                ! _parameters.hasParameter("if_available") )
            return false;

        if (_parameters.hasParameter("required"))
        {
            String[] values = _parameters.getParameterValue("required").split(",");
            for (int i = 0; i < values.length; i++)
            {
                String value = multivalDecode(values[i]);
                if ( ! _parameters.hasParameter("fetch." + value) )
                    return false;
            }
        }

        if ( _parameters.hasParameter("if_available"))
        {
            String[] values = _parameters.getParameterValue("if_available").split(",");
            for (int i = 0; i < values.length; i++)
            {
                String value = multivalDecode(values[i]);
                if ( ! _parameters.hasParameter("fetch." + value) )
                    return false;
            }
        }

        Iterator it = _parameters.getParameters().iterator();
        while (it.hasNext())
        {
            String paramName = ((Parameter) it.next()).getKey();
            if (! paramName.startsWith("fetch.") &&
                    ! paramName.equals("required") &&
                    ! paramName.equals("if_available") &&
                    ! paramName.equals("update_url"))
                return false;
        }

        return true;
    }
}
