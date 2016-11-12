package org.apache.velocity.runtime.resource;

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

import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.exception.VelocityException;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.RuntimeServices;
import org.apache.velocity.runtime.resource.loader.ResourceLoader2;
import org.apache.velocity.runtime.resource.loader.ResourceLoaderFactory;
import org.apache.velocity.util.ClassUtils;
import org.apache.velocity.util.ExtProperties;
import org.apache.velocity.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;


/**
 * Class to manage the text resource for the Velocity Runtime.
 *
 * @author  <a href="mailto:wglass@forio.com">Will Glass-Husain</a>
 * @author  <a href="mailto:jvanzyl@apache.org">Jason van Zyl</a>
 * @author  <a href="mailto:paulo.gaspar@krankikom.de">Paulo Gaspar</a>
 * @author  <a href="mailto:geirm@optonline.net">Geir Magnusson Jr.</a>
 * @author  <a href="mailto:henning@apache.org">Henning P. Schmiedehausen</a>
 * @version  $Id$
 */
public class ResourceManagerImpl
    implements ResourceManager
{

    /** A template resources. */
    public static final int RESOURCE_TEMPLATE = 1;

    /** A static content resource. */
    public static final int RESOURCE_CONTENT = 2;

    /** Object implementing ResourceCache to be our resource manager's Resource cache. */
    protected ResourceCache globalCache = null;

    /** The List of templateLoaders that the Runtime will use to locate the InputStream source of a template. */
    protected final List resourceLoaders = new ArrayList();

    /**
     * This is a list of the template input stream source initializers, basically properties for a particular template stream
     * source. The order in this list reflects numbering of the properties i.e.
     *
     * <p>&lt;loader-id&gt;.resource.loader.&lt;property&gt; = &lt;value&gt;</p>
     */
    private final List sourceInitializerList = new ArrayList();

    /**
     * Has this Manager been initialized?
     */
    private boolean isInit = false;

    /** switch to turn off log notice when a resource is found for the first time. */
    private boolean logWhenFound = true;

    /** The internal RuntimeServices object. */
    protected RuntimeServices rsvc = null;

    /** Logging. */
    protected Logger log = null;

    /**
     * Initialize the ResourceManager.
     *
     * @param  rsvc  The Runtime Services object which is associated with this Resource Manager.
     */
    public synchronized void initialize(final RuntimeServices rs)
    {
        if (isInit)
        {
            log.debug("Re-initialization of ResourceLoader attempted and ignored.");
            return;
        }

        ResourceLoader2 resourceLoader = null;

        rsvc = rs;
        log = rsvc.getLog("loader");

        log.trace("ResourceManager initializing: {}", this.getClass());

        assembleResourceLoaderInitializers();

        for (Iterator it = sourceInitializerList.iterator(); it.hasNext();)
        {
            /**
             * Resource loader can be loaded either via class name or be passed
             * in as an instance.
             */
            ExtProperties configuration = (ExtProperties) it.next();

            String loaderClass = StringUtils.nullTrim(configuration.getString("class"));
            ResourceLoader2 loaderInstance = (ResourceLoader2) configuration.get("instance");

            if (loaderInstance != null)
            {
                resourceLoader = loaderInstance;
            }
            else if (loaderClass != null)
            {
                resourceLoader = ResourceLoaderFactory.getLoader(rsvc, loaderClass);
            }
            else
            {
                String msg = "Unable to find '" +
                          configuration.getString(RuntimeConstants.RESOURCE_LOADER_IDENTIFIER) +
                          ".resource.loader.class' specification in configuration." +
                          " This is a critical value.  Please adjust configuration.";
                log.error(msg);
                throw new VelocityException(msg);
            }

            resourceLoader.commonInit(rsvc, configuration);
            resourceLoader.init(configuration);
            resourceLoaders.add(resourceLoader);
        }

        /*
         * now see if this is overridden by configuration
         */

        logWhenFound = rsvc.getBoolean(RuntimeConstants.RESOURCE_MANAGER_LOGWHENFOUND, true);

        /*
         *  now, is a global cache specified?
         */

        String cacheClassName = rsvc.getString(RuntimeConstants.RESOURCE_MANAGER_CACHE_CLASS);

        Object cacheObject = null;

        if (org.apache.commons.lang3.StringUtils.isNotEmpty(cacheClassName))
        {
            try
            {
                cacheObject = ClassUtils.getNewInstance(cacheClassName);
            }
            catch (ClassNotFoundException cnfe)
            {
                String msg = "The specified class for ResourceCache (" + cacheClassName +
                          ") does not exist or is not accessible to the current classloader.";
                log.error(msg, cnfe);
                throw new VelocityException(msg, cnfe);
            }
            catch (IllegalAccessException ae)
            {
                throw new VelocityException("Could not access class '"
                    + cacheClassName + "'", ae);
            }
            catch (InstantiationException ie)
            {
                throw new VelocityException("Could not instantiate class '"
                    + cacheClassName + "'", ie);
            }

            if (!(cacheObject instanceof ResourceCache))
            {
                String msg = "The specified resource cache class (" + cacheClassName +
                          ") must implement " + ResourceCache.class.getName();
                log.error(msg);
                throw new RuntimeException(msg);
            }
        }

        /*
         *  if we didn't get through that, just use the default.
         */
        if (cacheObject == null)
        {
            cacheObject = new ResourceCacheImpl();
        }

        globalCache = (ResourceCache) cacheObject;

        globalCache.initialize(rsvc);

        log.trace("Default ResourceManager initialization complete.");
    }

    /**
     * This will produce a List of Hashtables, each hashtable contains the initialization info for a particular resource loader. This
     * Hashtable will be passed in when initializing the the template loader.
     */
    private void assembleResourceLoaderInitializers()
    {
        Vector resourceLoaderNames = rsvc.getConfiguration().getVector(RuntimeConstants.RESOURCE_LOADER);
        StringUtils.trimStrings(resourceLoaderNames);

        for (Iterator it = resourceLoaderNames.iterator(); it.hasNext(); )
        {

            /*
             * The loader id might look something like the following:
             *
             * file.resource.loader
             *
             * The loader id is the prefix used for all properties
             * pertaining to a particular loader.
             */
            String loaderName = (String) it.next();
            StringBuffer loaderID = new StringBuffer(loaderName);
            loaderID.append(".").append(RuntimeConstants.RESOURCE_LOADER);

            ExtProperties loaderConfiguration =
        		rsvc.getConfiguration().subset(loaderID.toString());

            /*
             *  we can't really count on ExtendedProperties to give us an empty set
             */
            if (loaderConfiguration == null)
            {
                log.debug("ResourceManager : No configuration information found "+
                          "for resource loader named '{}' (id is {}). Skipping it...",
                          loaderName, loaderID);
                continue;
            }

            /*
             *  add the loader name token to the initializer if we need it
             *  for reference later. We can't count on the user to fill
             *  in the 'name' field
             */

            loaderConfiguration.setProperty(RuntimeConstants.RESOURCE_LOADER_IDENTIFIER, loaderName);

            /*
             * Add resources to the list of resource loader
             * initializers.
             */
            sourceInitializerList.add(loaderConfiguration);
        }
    }

    /**
     * Gets the named resource. Returned class type corresponds to specified type (i.e. <code>Template</code> to <code>
     * RESOURCE_TEMPLATE</code>).
     *
     * This method is now unsynchronized which requires that ResourceCache
     * implementations be thread safe (as the default is).
     *
     * @param  resourceName  The name of the resource to retrieve.
     * @param  resourceType  The type of resource (<code>RESOURCE_TEMPLATE</code>, <code>RESOURCE_CONTENT</code>, etc.).
     * @param  encoding  The character encoding to use.
     *
     * @return  Resource with the template parsed and ready.
     *
     * @throws  ResourceNotFoundException  if template not found from any available source.
     * @throws  ParseErrorException  if template cannot be parsed due to syntax (or other) error.
     */
    public Resource getResource(final String resourceName, final int resourceType, final String encoding)
        throws ResourceNotFoundException,
            ParseErrorException
    {
        /*
         * Check to see if the resource was placed in the cache.
         * If it was placed in the cache then we will use
         * the cached version of the resource. If not we
         * will load it.
         *
         * Note: the type is included in the key to differentiate ContentResource
         * (static content from #include) with a Template.
         */

        String resourceKey = resourceType + resourceName;
        Resource resource = globalCache.get(resourceKey);

        if (resource != null)
        {
            try
            {
                // avoids additional method call to refreshResource
                if (resource.requiresChecking())
                {
                    /*
                     * both loadResource() and refreshResource() now return
                     * a new Resource instance when they are called
                     * (put in the cache when appropriate) in order to allow
                     * several threads to parse the same template simultaneously.
                     * It is redundant work and will cause more garbage collection but the
                     * benefit is that it allows concurrent parsing and processing
                     * without race conditions when multiple requests try to
                     * refresh/load the same template at the same time.
                     *
                     * Another alternative is to limit template parsing/retrieval
                     * so that only one thread can parse each template at a time
                     * but that creates a scalability bottleneck.
                     *
                     * See VELOCITY-606, VELOCITY-595 and VELOCITY-24
                     */
                    resource = refreshResource(resource, encoding);
                }
            }
            catch (ResourceNotFoundException rnfe)
            {
                /*
                 *  something exceptional happened to that resource
                 *  this could be on purpose,
                 *  so clear the cache and try again
                 */

                globalCache.remove(resourceKey);

                return getResource(resourceName, resourceType, encoding);
            }
            catch (ParseErrorException pee)
            {
                log.error("ResourceManager.getResource() exception", pee);
                throw pee;
            }
            catch (RuntimeException re)
            {
                log.error("ResourceManager.getResource() exception", re);
        	    throw re;
            }
        }
        else
        {
            try
            {
                /*
                 *  it's not in the cache, so load it.
                 */
                resource = loadResource(resourceName, resourceType, encoding);

                if (resource.getResourceLoader().isCachingOn())
                {
                    globalCache.put(resourceKey, resource);
                }
            }
            catch (ResourceNotFoundException rnfe)
            {
                log.error("ResourceManager : unable to find resource '" +
                          resourceName + "' in any resource loader.");
                throw rnfe;
            }
            catch (ParseErrorException pee)
            {
                log.error("ResourceManager: parse exception: {}", pee.getMessage());
                throw pee;
            }
            catch (RuntimeException re)
            {
                log.error("ResourceManager.getResource() load exception", re);
                throw re;
            }
        }

        return resource;
    }

    /**
     * Create a new Resource of the specified type.
     *
     * @param  resourceName  The name of the resource to retrieve.
     * @param  resourceType  The type of resource (<code>RESOURCE_TEMPLATE</code>, <code>RESOURCE_CONTENT</code>, etc.).
     * @return  new instance of appropriate resource type
     * @since 1.6
     */
    protected Resource createResource(String resourceName, int resourceType)
    {
        return ResourceFactory.getResource(resourceName, resourceType);
    }

    /**
     * Loads a resource from the current set of resource loaders.
     *
     * @param  resourceName  The name of the resource to retrieve.
     * @param  resourceType  The type of resource (<code>RESOURCE_TEMPLATE</code>, <code>RESOURCE_CONTENT</code>, etc.).
     * @param  encoding  The character encoding to use.
     *
     * @return  Resource with the template parsed and ready.
     *
     * @throws  ResourceNotFoundException  if template not found from any available source.
     * @throws  ParseErrorException  if template cannot be parsed due to syntax (or other) error.
     */
    protected Resource loadResource(String resourceName, int resourceType, String encoding)
        throws ResourceNotFoundException,
            ParseErrorException
    {
        Resource resource = createResource(resourceName, resourceType);
        resource.setRuntimeServices(rsvc);
        resource.setName(resourceName);
        resource.setEncoding(encoding);

        /*
         * Now we have to try to find the appropriate
         * loader for this resource. We have to cycle through
         * the list of available resource loaders and see
         * which one gives us a stream that we can use to
         * make a resource with.
         */

        long howOldItWas = 0;

        for (Iterator it = resourceLoaders.iterator(); it.hasNext();)
        {
            ResourceLoader2 resourceLoader = (ResourceLoader2) it.next();
            resource.setResourceLoader(resourceLoader);

            /*
             *  catch the ResourceNotFound exception
             *  as that is ok in our new multi-loader environment
             */

            try
            {

                if (resource.process())
                {
                    /*
                     *  FIXME  (gmj)
                     *  moved in here - technically still
                     *  a problem - but the resource needs to be
                     *  processed before the loader can figure
                     *  it out due to to the new
                     *  multi-path support - will revisit and fix
                     */

                    if (logWhenFound && log.isDebugEnabled())
                    {
                        log.debug("ResourceManager: found {} with loader {}",
                                  resourceName, resourceLoader.getClassName());
                    }

                    howOldItWas = resourceLoader.getLastModified(resource);

                    break;
                }
            }
            catch (ResourceNotFoundException rnfe)
            {
                /*
                 *  that's ok - it's possible to fail in
                 *  multi-loader environment
                 */
            }
        }

        /*
         * Return null if we can't find a resource.
         */
        if (resource.getData() == null)
        {
            throw new ResourceNotFoundException("Unable to find resource '" + resourceName + "'");
        }

        /*
         *  some final cleanup
         */

        resource.setLastModified(howOldItWas);
        resource.setModificationCheckInterval(resource.getResourceLoader().getModificationCheckInterval());

        resource.touch();

        return resource;
    }

    /**
     * Takes an existing resource, and 'refreshes' it. This generally means that the source of the resource is checked for changes
     * according to some cache/check algorithm and if the resource changed, then the resource data is reloaded and re-parsed.
     *
     * @param  resource  resource to refresh
     * @param  encoding  character encoding of the resource to refresh.
     *
     * @throws  ResourceNotFoundException  if template not found from current source for this Resource
     * @throws  ParseErrorException  if template cannot be parsed due to syntax (or other) error.
     */
    protected Resource refreshResource(Resource resource, final String encoding)
        throws ResourceNotFoundException, ParseErrorException
    {
        /*
         * The resource knows whether it needs to be checked
         * or not, and the resource's loader can check to
         * see if the source has been modified. If both
         * these conditions are true then we must reload
         * the input stream and parse it to make a new
         * AST for the resource.
         */

        /*
         *  touch() the resource to reset the counters
         */
        resource.touch();

        /* check whether this can now be found in a higher priority
         * resource loader.  if so, pass the request off to loadResource.
         */
        ResourceLoader2 loader = resource.getResourceLoader();
        if (resourceLoaders.size() > 0 && resourceLoaders.indexOf(loader) > 0)
        {
            String name = resource.getName();
            if (loader != getLoaderForResource(name))
            {
                return loadResource(name, resource.getType(), encoding);
            }
        }

        if (resource.isSourceModified())
        {
            /*
             *  now check encoding info.  It's possible that the newly declared
             *  encoding is different than the encoding already in the resource
             *  this strikes me as bad...
             */

            if (!org.apache.commons.lang3.StringUtils.equals(resource.getEncoding(), encoding))
            {
                log.warn("Declared encoding for template '{}' is different on reload. Old = '{}' New = '{}'",
                         resource.getName(), resource.getEncoding(), encoding);
                resource.setEncoding(encoding);
            }

            /*
             *  read how old the resource is _before_
             *  processing (=>reading) it
             */
            long howOldItWas = loader.getLastModified(resource);

            String resourceKey = resource.getType() + resource.getName();

            /*
             * we create a copy to avoid partially overwriting a
             * template which may be in use in another thread
             */

            Resource newResource =
                ResourceFactory.getResource(resource.getName(), resource.getType());

            newResource.setRuntimeServices(rsvc);
            newResource.setName(resource.getName());
            newResource.setEncoding(resource.getEncoding());
            newResource.setResourceLoader(loader);
            newResource.setModificationCheckInterval(loader.getModificationCheckInterval());

            newResource.process();
            newResource.setLastModified(howOldItWas);
            resource = newResource;

            globalCache.put(resourceKey, newResource);
        }
        return resource;
    }

    /**
     * Determines if a template exists, and returns name of the loader that provides it. This is a slightly less hokey way to
     * support the Velocity.templateExists() utility method, which was broken when per-template encoding was introduced. We can
     * revisit this.
     *
     * @param  resourceName  Name of template or content resource
     *
     * @return  class name of loader than can provide it
     */
    public String getLoaderNameForResource(String resourceName)
    {
        ResourceLoader2 loader = getLoaderForResource(resourceName);
        if (loader == null)
        {
            return null;
        }
        return loader.getClass().toString();
    }

    /**
     * Returns the first {@link ResourceLoader2} in which the specified
     * resource exists.
     */
    private ResourceLoader2 getLoaderForResource(String resourceName)
    {
        for (Iterator i = resourceLoaders.iterator(); i.hasNext(); )
        {
            ResourceLoader2 loader = (ResourceLoader2)i.next();
            if (loader.resourceExists(resourceName))
            {
                return loader;
            }
        }
        return null;
    }

}
