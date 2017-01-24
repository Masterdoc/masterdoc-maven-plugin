package fr.masterdocs.plugin;

/*-
 * #%L
 * masterdocs
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2017 masterdocs
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the masterdocs nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

import com.github.jknack.handlebars.*;
import com.github.jknack.handlebars.io.FileTemplateLoader;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.googlecode.gentyref.GenericTypeReflector;
import fr.masterdocs.pojo.*;
import fr.masterdocs.pojo.Enumeration;
import org.apache.http.client.utils.URIBuilder;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.reflections.Reflections;

import javax.ws.rs.*;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;
import java.beans.IntrospectionException;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.google.common.base.Defaults.defaultValue;
import static java.io.File.separator;
import static java.text.MessageFormat.format;
import static org.springframework.util.StringUtils.capitalize;

public class MasterDocGenerator {

    // ----------------------------------------------------------------------
    // Constants
    // ----------------------------------------------------------------------

    public static final String CLASS = "class ";
    public static final SimpleDateFormat SDF = new SimpleDateFormat("dd-MM-yyyy HH:mm:sss");
    public static final String MASTERDOC_JSON_FILENAME = "masterdocs.json";
    public static final String PATH_PARAM = "PathParam";
    public static final String QUERY_PARAM = "QueryParam";
    public static final String GET = "GET";
    public static final String POST = "POST";
    public static final String PUT = "PUT";
    public static final String DELETE = "DELETE";
    public static final String OPTIONS = "OPTIONS";
    public static final String NULL = "null";
    public static final String VOID = "void";
    public static final String IS_PREFIX = "is";
    public static final String GET_PREFIX = "get";
    public static final String INTERFACE = "interface";
    public static final String T = "T";
    public static final String JAVA_LANG_OBJECT = "java.lang.Object";
    public static final String JAVA = "java";
    public static final String BYTE = "B";
    public static final String ARRAY = "[]";
    public static final String DOT = ".";
    public static final String COMMA = ",";
    public static final String MASTERDOCS_DIR = "masterdocs";
    public static final String JAVA_UTIL_HASH_MAP = "java.util.HashMap";
    public static final String JAVA_UTIL_MAP = "java.util.Map";
    private static final String JAVA_UTIL_LIST = "java.util.List";
    private static final String JAVA_UTIL_SET = "java.util.Set";
    private static final String JAVA_UTIL_COLLECTION = "java.util.Collection";
    public Integer MAX_DEPTH = 1;

    // ----------------------------------------------------------------------
    // Variables
    // ----------------------------------------------------------------------
    /**
     * Logger for maven plugin.
     */
    private ConsoleLogger consoleLogger = new ConsoleLogger();
    /**
     * Resources found by MasterDoc.
     */
    private List<Resource> resources;
    /**
     * Entities found by MasterDoc.
     */
    private List<AbstractEntity> entities;
    /**
     * Metadata found by MasterDoc.
     */
    private MasterDocMetadata metadata;
    /**
     * Final MasterDoc.
     */
    private Set<String> entityList;
    private MavenProject project;
    private ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
    private ClassLoader newClassLoader;
    private String pathToGenerateFile;
    private HashSet<String> newEntities;

    // ----------------------------------------------------------------------
    // Constructors
    // ----------------------------------------------------------------------

    public MasterDocGenerator() {

    }

    public MasterDocGenerator(MavenProject project, String pathToGenerateFile, String[] packageDocumentationResources, boolean generateHTMLSite, Integer maxDepth)
    throws SecurityException,
    NoSuchFieldException,
    IllegalArgumentException, IllegalAccessException {
        long start = System.currentTimeMillis();
        consoleLogger.info("MasterDocGenerator started");
        resources = new ArrayList<Resource>();
        entities = new ArrayList<AbstractEntity>();
        entityList = new HashSet<String>();
        this.project = project;
        this.pathToGenerateFile = pathToGenerateFile;
        if (null != maxDepth) {
            this.MAX_DEPTH = maxDepth;
        }
        // ////////////////////
        generateProjectClassLoader(project);
        // ////////////////////
        for (String packageDocumentationResource : packageDocumentationResources) {
            consoleLogger.info(format("Generate REST documentation on package {0} ...", packageDocumentationResource));
            startGeneration(new String[]{packageDocumentationResource});
        }
        //
        generateDocumentationFile(generateHTMLSite);
        //
        // restore original classloader
        Thread.currentThread().setContextClassLoader(originalClassLoader);
        consoleLogger.info(format("Generation ended in {0} ms", (System.currentTimeMillis() - start)));
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        while (true) {
            int readCount = in.read(buffer);
            if (readCount < 0) {
                break;
            }
            out.write(buffer, 0, readCount);
        }
    }

    private static void copy(InputStream in, File file) throws IOException {
        OutputStream out = new FileOutputStream(file);
        try {
            copy(in, out);
        } finally {
            out.close();
        }
    }

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------
    public void startGeneration(String[] args) {
        String packageDocumentationResource = args[0];

        if (null != packageDocumentationResource
        && packageDocumentationResource.length() > 0) {
            try {
                getMetadata();
                getDocResource(packageDocumentationResource);
                getEntities(entityList);
                consoleLogger.info(format("Entities : {0}", entityList.size()));
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            } catch (IntrospectionException e) {
                e.printStackTrace();
            }
        } else {
            consoleLogger.error("packageDocumentationResources not defined in plugin configuration");
        }
    }

    // ----------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------
    private void getEntities(Set list) throws ClassNotFoundException,
    IntrospectionException {

        newEntities = new HashSet<String>();
        for (Iterator<Serializable> iterator = list.iterator(); iterator.hasNext(); ) {
            Serializable entity = iterator.next();
            final Class<?> entityClass;
            String entityToString = entity.toString();
            try {
                if (entityToString.startsWith(JAVA_UTIL_LIST)) {
                    entityToString = entityToString.substring(JAVA_UTIL_LIST.length() + 1, entityToString.length() - 1);
                }
                entityClass = Class.forName(entityToString, true, newClassLoader);

            } catch (Exception e) {
                consoleLogger.debug(format("{0} is not forNamable", entityToString));
                continue;
            }
            Entity newEntity = new Entity();
            if (!entityToString.startsWith(JAVA)) {

                newEntity.setName(entityToString.replaceAll("\\$", "."));

                if (entityClass.isEnum()) {
                    extractEnumFields(entity);
                } else {
                    newEntity.setFields(extractFields(entityClass));
                    final Class<?> superclass = entityClass.getSuperclass();
                    if (superclass != null && !JAVA_LANG_OBJECT.equals(superclass.getName())) {
                        newEntity.setSuperClass(superclass.getName());
                        if (!entityList.contains(superclass.getName()) && !newEntities.contains(superclass.getName())) {
                            newEntities.add(superclass.getName());
                        }
                    }
                    if (superclass == null) {
                        final Annotation[] annotations = entityClass.getAnnotations();
                        if (annotations != null) {
                            for (int i = 0; i < annotations.length; i++) {
                                Annotation annotation = annotations[i];
                                if (annotation.toString().contains("JsonSubTypes")) {
                                    newEntity.setSubType(new ArrayList<String>());
                                    final com.fasterxml.jackson.annotation.JsonSubTypes.Type[] value = ((com.fasterxml.jackson.annotation.JsonSubTypes) annotation).value();
                                    for (int j = 0; j < value.length; j++) {
                                        com.fasterxml.jackson.annotation.JsonSubTypes.Type type = value[j];
                                        String subType = type.value().toString();
                                        if (subType.startsWith(CLASS)) {
                                            subType = subType.substring(subType.indexOf(CLASS) + CLASS.length());
                                        }
                                        newEntities.add(subType);
                                        newEntity.getSubType().add(subType);
                                    }
                                }
                            }
                        }
                    }
                    if (newEntity.getFields().isEmpty()) {
                        newEntity.setFields(null);
                    }
                    entities.add(newEntity);

                }
            }
        }
        if (newEntities.size() > 0) {
            entityList.addAll(newEntities);
            getEntities((Set) newEntities.clone());
        }
    }

    private String extractName(String fqn) {
        if (null != fqn) {
            final int indexOfDOT = fqn.lastIndexOf(DOT);
            if (indexOfDOT > 0 && indexOfDOT < fqn.length() - 1)
                return fqn.substring(indexOfDOT + 1);
        }
        return fqn;
    }

    /**
     * Get all @Path class and export all methods
     *
     * @throws NoSuchFieldException
     * @throws SecurityException
     */

    private void getDocResource(String packageDocumentationResource) {
        String mediaTypeProduces = null, mediaTypeConsumes = null;

        Reflections reflections = new Reflections(packageDocumentationResource);
        Set<Class<?>> reflectionResources = reflections
        .getTypesAnnotatedWith(Path.class);
        consoleLogger.info(format("Resources : {0}", reflectionResources));
        for (Iterator<Class<?>> iterator = reflectionResources.iterator(); iterator.hasNext(); ) {
            Class<?> resource = iterator.next();
            if (!resource.isInterface()) {
                Resource res = new Resource();

                Annotation[] annotations = resource.getAnnotations();
                Method[] declaredMethods = resource.getDeclaredMethods();

                res.setEntryList(new TreeMap<String, List<ResourceEntry>>());
                // Annotations for resource
                for (int i = 0; i < annotations.length; i++) {
                    Annotation annotation = annotations[i];
                    if (annotation instanceof Path) {
                        String rootPath = ((Path) annotation).value();
                        if (null != rootPath && !rootPath.endsWith("/")) {
                            rootPath = rootPath + "/";
                        }
                        res.setRootPath(rootPath);
                    }
                    if (annotation instanceof Produces) {
                        mediaTypeProduces = ((Produces) annotation).value()[0];
                    }
                    if (annotation instanceof Consumes) {
                        mediaTypeConsumes = ((Consumes) annotation).value()[0];
                    }
                }

                // Check methods
                Class<?> superclass = resource.getSuperclass();
                if (null != superclass && !superclass.getCanonicalName().equals(JAVA_LANG_OBJECT)) {
                    Method[] superclassDeclaredMethods = superclass.getDeclaredMethods();
                    for (int i = 0; i < superclassDeclaredMethods.length; i++) {
                        Method superclassDeclaredMethod = superclassDeclaredMethods[i];
                        ResourceEntry resourceEntry = createResourceEntryFromMethod(superclassDeclaredMethod, mediaTypeConsumes, mediaTypeProduces, resource);
                        if (null != resourceEntry) {
                            String path = resourceEntry.getPath();
                            if (null != path && path.startsWith("/")) {
                                path = path.substring(1);
                            }
                            resourceEntry.setFullPath(res.getRootPath() + path);
                            if (!res.getEntryList().containsKey(resourceEntry.getFullPath())) {
                                res.getEntryList().put(resourceEntry.getFullPath(), new ArrayList<ResourceEntry>());
                            }
                            List<ResourceEntry> resourceEntries = res.getEntryList().get(resourceEntry.getFullPath());
                            res.getEntryList().put(resourceEntry.getFullPath(), addResourceEntries(resourceEntries, resourceEntry));
                        }
                    }
                }
                for (int i = 0; i < declaredMethods.length; i++) {
                    Method declaredMethod = declaredMethods[i];
                    ResourceEntry resourceEntry = createResourceEntryFromMethod(
                    declaredMethod, mediaTypeConsumes,
                    mediaTypeProduces, resource);
                    if (null != resourceEntry) {
                        if (res.getEntryList().containsKey(
                        resourceEntry.calculateUniqKey())) {
                            res.getEntryList().remove(
                            resourceEntry.calculateUniqKey());
                        }
                        String path = resourceEntry.getPath();
                        if (null != path && path.startsWith("/")) {
                            path = path.substring(1);
                        }
                        resourceEntry.setFullPath(res.getRootPath() + path);
                        if (!res.getEntryList().containsKey(resourceEntry.getFullPath())) {
                            res.getEntryList().put(resourceEntry.getFullPath(), new ArrayList<ResourceEntry>());
                        }
                        List<ResourceEntry> resourceEntries = res.getEntryList().get(resourceEntry.getFullPath());
                        res.getEntryList().put(resourceEntry.getFullPath(), addResourceEntries(resourceEntries, resourceEntry));
                    }
                }

                consoleLogger.debug(">> " + resource.getCanonicalName());
                resources.add(res);
                extractEntityFromResourceEntries(res);
            } else {
                consoleLogger.debug(">>skip " + resource.getCanonicalName());
            }
        }
    }

    private List<ResourceEntry> addResourceEntries(List<ResourceEntry> resourceEntries, ResourceEntry resourceEntry) {
        final Iterator<ResourceEntry> iterator = resourceEntries.iterator();
        while (iterator.hasNext()) {
            final ResourceEntry next = iterator.next();
            if (next.getVerb().equals(resourceEntry.getVerb())) {
                iterator.remove();
            }
        }

        resourceEntries.add(resourceEntry);

        return resourceEntries;
    }

    private void getMetadata() {
        metadata = new MasterDocMetadata();
        metadata.setGenerationDate(SDF.format(new Date()));
        metadata.setGroupId(project.getGroupId());
        metadata.setArtifactId(project.getArtifactId());
        metadata.setVersion(project.getVersion());

    }

    /**
     * Method to extract the class values.
     *
     * @param entityClass
     * @return
     * @throws ClassNotFoundException
     * @throws IntrospectionException
     */
    private Map<String, AbstractEntity> extractFields(Class<?> entityClass)
    throws ClassNotFoundException, IntrospectionException {
        Map<String, AbstractEntity> fields = new TreeMap<String, AbstractEntity>();
        consoleLogger.debug(format(">>Extract fields for class {0} ...", entityClass));

        final java.lang.reflect.Field[] declaredFields = entityClass.getDeclaredFields();
        for (int i = 0; i < declaredFields.length; i++) {
            java.lang.reflect.Field declaredField = declaredFields[i];
            final Annotation[] declaredAnnotations = declaredField.getDeclaredAnnotations();
            boolean bypass = false;
            String name = null;
            for (Annotation annotation : declaredAnnotations) {

                if (annotation.toString().contains("JsonIgnore")) {
                    bypass = true;
                }
                if (annotation instanceof XmlTransient) {
                    bypass = true;
                }
                if (annotation instanceof XmlElement) {
                    name = ((XmlElement) annotation).name();
                }
            }
            if (!bypass) {
                consoleLogger.debug(format(">>Extract fields  {0} ...", declaredField.getName()));
                Type typeOfField = declaredField.getGenericType();
                if (!typeOfField.toString().startsWith(CLASS) && !typeOfField.toString().startsWith(INTERFACE) && typeOfField.toString().indexOf(DOT) > -1) {
                    typeOfField = (ParameterizedType) typeOfField;
                }
                String type = extractTypeFromType(typeOfField);
                String typeDisplay = type;
                if (type.startsWith("[")) {
                    if (type.startsWith("[L") && type.endsWith(";")) {
                        type = type.substring(2, type.length() - 1);
                    } else {
                        type = type.substring(1);
                        if (BYTE.equals(type)) {
                            type = byte.class.getName();
                        }
                    }
                    typeDisplay = type + ARRAY;
                }
                if (type.indexOf("<") > -1) {
                    type = type.substring(type.indexOf("<") + 1, type.indexOf(">"));
                }
                String[] types = type.split(COMMA);
                if (isGetterSetterExist(entityClass, declaredField.getName())) {
                    createEntityFromField(fields, declaredField, typeDisplay, types, null != name ? name : declaredField.getName());
                }
            }
        }
        return fields;
    }

    private boolean isGetterSetterExist(Class<?> entityClass, String name) {
        try {
            entityClass.getDeclaredMethod(GET_PREFIX + capitalize(name)); // GET OR IS
            return true;
        } catch (NoSuchMethodException e) {
            try {
                entityClass.getDeclaredMethod(IS_PREFIX + capitalize(name)); // GET OR IS
                return true;
            } catch (NoSuchMethodException ex) {
                try {
                    entityClass.getDeclaredMethod(GET_PREFIX + name); // GET OR IS
                    return true;
                } catch (NoSuchMethodException exe) {
                    try {
                        entityClass.getDeclaredMethod(IS_PREFIX + name); // GET OR IS
                        return true;
                    } catch (NoSuchMethodException exec) {
                        try {
                            entityClass.getDeclaredMethod(name); // just name
                            return true;
                        } catch (NoSuchMethodException nsme) {
                        }
                    }
                }
                consoleLogger.debug(format(">>>>Bypass : {0}.{1}", entityClass.toString(), name));
            }

        }
        return false;
    }

    private void createEntityFromField(Map<String, AbstractEntity> fields, Field declaredField, String typeDisplay, String[] types, String name) {
        Class<?> currEntityClass = null;
        for (String t : types) {
            try {
                t = t.trim();
                currEntityClass = Class.forName(t, true, newClassLoader);
                if (!entityList.contains(t) && !newEntities.contains(t)) {
                    newEntities.add(t);
                }
            } catch (Exception e) {
                consoleLogger.debug(format("{0} is not forNamable", t.toString()));
                continue;
            }
        }
        AbstractEntity field;
        if (null != currEntityClass && currEntityClass.isEnum()) {
            field = new Enumeration();
            field.setName(typeDisplay.replaceAll("\\$", "."));
        } else {
            field = new Entity();
            field.setName(typeDisplay.replaceAll("\\$", "."));
        }
        fields.put(name, field);
    }

    /**
     * Method to extract the enuration values.
     *
     * @param entity
     * @return
     * @throws ClassNotFoundException
     * @throws IntrospectionException
     */
    private Map<String, AbstractEntity> extractEnumFields(Serializable entity)
    throws ClassNotFoundException, IntrospectionException {
        Map<String, AbstractEntity> fields = new TreeMap<String, AbstractEntity>();
        List<String> values = new ArrayList<String>();
        String entityString = entity.toString();
        if (entityString.startsWith(CLASS)) {
            entityString = entityString.substring(entityString.indexOf(CLASS) + CLASS.length());
        }
        consoleLogger.debug(">>>Extract enum " + entityString + " ...");
        final Class<?> entityClass = Class.forName(entityString, true, newClassLoader);
        final Object[] declaredEnumConstants = entityClass.getFields();
        Enumeration newEnumeration = new Enumeration();
        newEnumeration.setName(entityString.replaceAll("\\$", "."));
        for (int i = 0; i < declaredEnumConstants.length; i++) {
            values.add(extractName(declaredEnumConstants[i].toString()));
        }
        newEnumeration.setValues(values);
        // do not add an enum if its already in the entities list
        if (!entities.contains(newEnumeration)) {
            entities.add(newEnumeration);
        }
        return fields;
    }

    private void extractEntityFromResourceEntries(Resource res) {
        Map<String, List<ResourceEntry>> entryList = res.getEntryList();
        Set<String> set = entryList.keySet();
        for (Iterator<String> iterator = set.iterator(); iterator.hasNext(); ) {
            String key = iterator.next();
            List<ResourceEntry> resourceEntries = entryList.get(key);
            for (ResourceEntry resourceEntry : resourceEntries) {
                Serializable requestEntity = resourceEntry.getRequestEntity();
                Serializable responseEntity = resourceEntry.getResponseEntity();
                if (null != requestEntity && !NULL.equals(requestEntity) && !VOID.equals(requestEntity)) {
                    entityList.add(removeList(requestEntity));
                }
                if (null != responseEntity && !NULL.equals(responseEntity) && !VOID.equals(responseEntity)) {
                    entityList.add(removeList(responseEntity));
                }
            }
        }
    }

    private String removeList(Serializable entity) {
        String string = String.valueOf(entity);
        if (string.indexOf("<") > 0) {
            string = string.substring(string.indexOf("<") + 1,
            string.length() - 1);
        }
        return string;
    }

    private ResourceEntry createResourceEntryFromMethod(Method declaredMethod,
                                                        String mediaTypeConsumes, String mediaTypeProduces,
                                                        Class childResourceClass) {
        ResourceEntry resourceEntry = new ResourceEntry(mediaTypeConsumes,
        mediaTypeProduces);
        resourceEntry.setPath("/");
        Annotation[] declaredAnnotations = declaredMethod.getDeclaredAnnotations();
        for (int i = 0; i < declaredAnnotations.length; i++) {
            Annotation declaredAnnotation = declaredAnnotations[i];
            if (declaredAnnotation instanceof Path) {
                String path = ((Path) declaredAnnotation).value();
                if (null != path && !path.startsWith("/")) {
                    path = "/" + path;
                }
                resourceEntry.setPath(path);
            }
            if (declaredAnnotation instanceof Produces) {
                resourceEntry
                .setMediaTypeProduces(((Produces) declaredAnnotation)
                .value()[0]);
            }
            if (declaredAnnotation instanceof Consumes) {
                resourceEntry
                .setMediaTypeConsumes(((Consumes) declaredAnnotation)
                .value()[0]);
            }
            if (declaredAnnotation instanceof GET) {
                resourceEntry.setVerb(GET);
            }
            if (declaredAnnotation instanceof POST) {
                resourceEntry.setVerb(POST);
            }
            if (declaredAnnotation instanceof PUT) {
                resourceEntry.setVerb(PUT);
            }
            if (declaredAnnotation instanceof DELETE) {
                resourceEntry.setVerb(DELETE);
            }
            if (declaredAnnotation instanceof OPTIONS) {
                resourceEntry.setVerb(OPTIONS);
            }
        }

        if (null == resourceEntry.getVerb()) {
            return null;
        }

        Object[] pType;
        if (null != childResourceClass) {
            pType = GenericTypeReflector.getExactParameterTypes(declaredMethod,
            childResourceClass);
        } else {
            pType = declaredMethod.getParameterTypes();
        }

        Annotation[][] pAnnot = declaredMethod.getParameterAnnotations();
        for (int i = 0; i < pType.length; i++) {

            String typeName = "";
            if (pType[i] instanceof Class) {
                typeName = ((Class) pType[i]).getName();
            } else {
                typeName = extractTypeFromType(((ParameterizedType) pType[i]));
            }
            if (JAVA_UTIL_HASH_MAP.equals(typeName)) {
                Type[] types = declaredMethod.getGenericParameterTypes();
                ParameterizedType paramType = (ParameterizedType) types[i];
                typeName = extractTypeFromType(paramType);
            }
            boolean isAParam = false;
            for (int j = 0; j < pAnnot[i].length; j++) {
                Annotation annotation = pAnnot[i][j];
                if (annotation instanceof PathParam) {
                    Param param = new Param();
                    param.setType(PATH_PARAM);
                    param.setClassName(typeName);
                    param.setName(((PathParam) annotation).value());
                    resourceEntry.getPathParams().add(param);
                    isAParam = true;
                }
                if (annotation instanceof QueryParam) {
                    Param param = new Param();
                    param.setType(QUERY_PARAM);
                    param.setClassName(typeName);
                    param.setName(((QueryParam) annotation).value());
                    resourceEntry.getQueryParams().add(param);
                    isAParam = true;
                }
                if (annotation instanceof javax.ws.rs.core.Context) {
                    isAParam = true;
                }
            }
            if (!isAParam) {
                if (typeName.startsWith("[")) {
                    if (typeName.startsWith("[L") && typeName.endsWith(";")) {
                        typeName = typeName.substring(2, typeName.length() - 1);
                    } else {
                        typeName = typeName.substring(1);
                        if (BYTE.equals(typeName)) {
                            typeName = byte.class.getName();
                        }
                    }
                    typeName = typeName + ARRAY;
                }
                resourceEntry.setRequestEntity(typeName);
            }
            if (!entityList.contains(typeName)) {
                entityList.add(typeName);
            }
        }

        if (null != childResourceClass) {
            Type exactReturnType = GenericTypeReflector.getExactReturnType(
            declaredMethod, childResourceClass);
            resourceEntry
            .setResponseEntity(extractTypeFromType(exactReturnType));
        } else {
            resourceEntry.setResponseEntity(extractTypeFromType(declaredMethod
            .getGenericReturnType()));
        }

        resourceEntry.setMethodName(declaredMethod.getName());
        return resourceEntry;
    }

    private String extractTypeFromType(Type type) {
        String returnType = null;
        if (T.equals(type.toString())) {
            return JAVA_LANG_OBJECT;
        }
        if (type instanceof ParameterizedType) {
            returnType = type.toString();
            if (returnType.startsWith(CLASS)) {
                returnType = returnType.substring(returnType.indexOf(CLASS));
            }
        } else {
            returnType = ((Class) type).getName();
        }
        return returnType;
    }

    /**
     * @param project
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     */
    private void generateProjectClassLoader(MavenProject project) throws NoSuchFieldException, IllegalAccessException {
        List<URL> urls = new ArrayList<URL>();

        // get all the dependencies which are hidden in resolvedArtifacts of project object
        Field dependencies = MavenProject.class.
        getDeclaredField("resolvedArtifacts");

        dependencies.setAccessible(true);

        LinkedHashSet<Artifact> artifacts = (LinkedHashSet<Artifact>) dependencies.get(project);

        // noinspection unchecked
        for (Artifact artifact : artifacts) {
            try {
                urls.add(artifact.getFile().toURI().toURL());
            } catch (MalformedURLException e) {
                // logger.error(e);
            }
        }

        try {
            urls.add(new File(project.getBuild().getOutputDirectory()).toURI().toURL());
        } catch (MalformedURLException e) {
            consoleLogger.error(e.getMessage());
        }

        consoleLogger.debug("urls = \n" + urls.toString().replace(",", "\n"));

        newClassLoader = new URLClassLoader(urls.toArray(new URL[urls.size()]), originalClassLoader);
        Thread.currentThread().setContextClassLoader(newClassLoader);
    }

    /**
     * @param generateHTMLSite
     */
    private void generateDocumentationFile(boolean generateHTMLSite) {
        JacksonJsonProvider jsonProvider = new JacksonJsonProvider();
        ObjectMapper mapper = jsonProvider.getObjectMapper();
        MasterDoc masterDoc = new MasterDoc();
        Function<AbstractEntity, String> getNameFunction = new Function<AbstractEntity, String>() {
            public String apply(AbstractEntity from) {
                return extractName(from.getName()) + from.getName();
            }
        };

        Ordering<AbstractEntity> nameOrdering = Ordering.natural().onResultOf(getNameFunction);

        ImmutableSortedSet<AbstractEntity> sortedEntities = ImmutableSortedSet.orderedBy(nameOrdering).addAll(entities).build();
        masterDoc.setEntities(sortedEntities.asList());
        Function<Resource, String> getPathFunction = new

        Function<Resource, String>() {
            public String apply(Resource from) {
                return from.getRootPath();
            }
        };

        Ordering<Resource> pathOrdering = Ordering.natural().onResultOf(getPathFunction);

        ImmutableSortedSet<Resource> sortedResources = ImmutableSortedSet.orderedBy(pathOrdering).addAll(resources).build();
        masterDoc.setResources(sortedResources.asList());
        masterDoc.setMetadata(metadata);
        consoleLogger.info(format("Generate files in {0} ...", pathToGenerateFile));

        File theDir = new File(pathToGenerateFile);
        // if the directory does not exist, create it
        theDir.mkdirs();
        try {
            File fileEntities = new File(pathToGenerateFile + separator + MASTERDOC_JSON_FILENAME);
            BufferedWriter output = new BufferedWriter(new FileWriter(
            fileEntities));
            output.write(mapper.defaultPrettyPrintingWriter()
            .writeValueAsString(masterDoc));
            output.close();
            if (generateHTMLSite) {
                consoleLogger.debug("Start HTMLSite generation");
                final URL location = getClass().getProtectionDomain().getCodeSource().getLocation();
                final ZipFile zipFile = new ZipFile(location.getPath());
                final java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().startsWith(MASTERDOCS_DIR)) {
                        consoleLogger.debug(format("Copy file : {0}", entry.getName()));
                        File file = new File(pathToGenerateFile + separator, entry.getName());
                        if (entry.isDirectory()) {
                            file.mkdirs();
                        } else {
                            file.getParentFile().mkdirs();
                            InputStream in = zipFile.getInputStream(entry);
                            try {
                                copy(in, file);
                            } finally {
                                in.close();
                            }
                        }
                    }

                }
                String handlebarsTemplate = pathToGenerateFile + separator + MASTERDOCS_DIR + separator;
                handleBarsApply(masterDoc, handlebarsTemplate);

                consoleLogger.info(format("HTMLSite generate in {0}", pathToGenerateFile));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    protected String handleBarsApply(MasterDoc masterDoc, String handlebarsTemplate) throws IOException {
        final FileTemplateLoader fileTemplateLoader = new FileTemplateLoader(handlebarsTemplate);
        Handlebars handlebars = new Handlebars(fileTemplateLoader);
        handlebars.registerHelper("isEnum", new Helper<AbstractEntity>() {
            @Override
            public CharSequence apply(AbstractEntity abstractEntity, Options options) throws IOException {
                if (abstractEntity instanceof Enumeration)
                    return options.fn();
                else
                    return options.inverse();
            }
        });

        handlebars.registerHelper("getLabelColor", new Helper<ResourceEntry>() {
            @Override
            public CharSequence apply(ResourceEntry entry, Options options) throws IOException {
                if (GET.equals(entry.getVerb())) {
                    return "green";
                }
                if (DELETE.equals(entry.getVerb())) {
                    return "red";
                }
                if (POST.equals(entry.getVerb())) {
                    return "blue";
                }
                if (PUT.equals(entry.getVerb())) {
                    return "orange";
                }
                if (OPTIONS.equals(entry.getVerb())) {
                    return "grey";
                }
                return "grey";
            }
        });

        handlebars.registerHelper("extractName", new Helper<String>() {
            @Override
            public CharSequence apply(String context, Options options) throws IOException {
                return extractNameFromFQN(context);
            }
        });

        handlebars.registerHelper("replaceDot", new Helper<String>() {
            @Override
            public CharSequence apply(String context, Options options) throws IOException {
                return context.replaceAll("\\.", "");
            }
        });

        handlebars.registerHelper("generateResID", new Helper<Resource>() {
            @Override
            public CharSequence apply(Resource context, Options options) throws IOException {
                return context.getRootPath().replaceAll("/", "_").replaceAll("\\{", "").replaceAll("}", "");
            }
        });

        handlebars.registerHelper("generateResEntryID", new Helper<ResourceEntry>() {
            @Override
            public CharSequence apply(ResourceEntry context, Options options) throws IOException {
                return context.calculateUniqKey();
            }
        });

        handlebars.registerHelper("decorate", new Helper<String>() {
            @Override
            public CharSequence apply(String context, Options options) throws IOException {
                return decorateURL(context);
            }
        });

        handlebars.registerHelper("generateResEntryID", new Helper<ResourceEntry>() {
            @Override
            public CharSequence apply(ResourceEntry context, Options options) throws IOException {
                return new Handlebars.SafeString((context.getFullPath() + context.calculateUniqKey()).replaceAll("<<", "").replaceAll("/", "").replaceAll("}", "")
                .replaceAll("\\{", "")
                .replaceAll(":", "")
                .replaceAll("\\+", "")
                .replaceAll("\\{", "").replaceAll("\\\\", ""));
            }
        });

        handlebars.registerHelper("URL", new Helper<ResourceEntry>() {
            @Override
            public CharSequence apply(ResourceEntry context, Options options) throws IOException {
                URIBuilder uriBuilder = new URIBuilder();
                for (Param queryParam : context.getQueryParams()) {
                    uriBuilder.addParameter(queryParam.getName(), "value");
                }
                uriBuilder.setPath(context.getFullPath());
                try {
                    return decorateURL(uriBuilder.build().toString().replaceAll("%7B", "{").replaceAll("%7D", "}"));
                } catch (URISyntaxException e) {
                    return decorateURL(context.getFullPath());
                }
            }
        });

        handlebars.registerHelper("JSON", new Helper<String>() {

            @Override
            public CharSequence apply(String context, Options options) throws IOException {

                try {
                    JSONObject jso = new JSONObject();
                    JSONArray jsa = null;
                    if (context.startsWith(JAVA_UTIL_HASH_MAP) ||
                    context.startsWith(JAVA_UTIL_MAP) ||
                    context.startsWith(JAVA_UTIL_SET) ||
                    context.startsWith(JAVA_UTIL_LIST) ||
                    context.startsWith(JAVA_UTIL_COLLECTION)) {
                        jsa = new JSONArray();
                        final int beginIndex = context.indexOf("<") + 1;
                        final int finishIndex = context.indexOf(">");
                        context = context.substring(beginIndex, finishIndex);
                    }
                    final AbstractEntity entity = extractEntity(context);
                    if (null != entity) {
                        if (entity instanceof Entity) {
                            try {
                                final TreeMap<String, Integer> depthObj = new TreeMap<String, Integer>();
                                final Object jsonFromEntity = getJSONFromEntity((Entity) entity, depthObj);
                                if (jsonFromEntity instanceof JSONObject) {
                                    jso = (JSONObject) jsonFromEntity;
                                } else {
                                    jsa = (JSONArray) jsonFromEntity;
                                }
                            } catch (JSONException e) {

                            }
                            if (null != jsa) {
                                jsa.put(jso);
                                return jsa.toString();
                            }
                            return jso.toString();
                        } else {
                            return ((Enumeration) entity).getValues().get(0);
                        }
                    }
                } catch (Exception e) {
                    return context;
                }
                return null;
            }
        });

        handlebars.registerHelper("LINK", new Helper<AbstractEntity>() {

            @Override
            public CharSequence apply(AbstractEntity context, Options options) throws IOException {
                String name = context.getName();
                if (name.startsWith(JAVA_UTIL_HASH_MAP) ||
                name.startsWith(JAVA_UTIL_MAP) ||
                name.startsWith(JAVA_UTIL_SET) ||
                name.startsWith(JAVA_UTIL_LIST) ||
                name.startsWith(JAVA_UTIL_COLLECTION)) {

                    final int beginIndex = name.indexOf("<") + 1;
                    final int finishIndex = name.indexOf(">");
                    name = name.substring(beginIndex, finishIndex);
                }

                return "<a href=\"#" + name + "\">";
            }
        });

        Template template = handlebars.compile("index");
        Context ctx = Context.newContext(masterDoc);
        String newIndex = template.apply(ctx);
        File indexFile = new File(handlebarsTemplate + "index.html");
        FileWriter fw = new FileWriter(indexFile.getAbsoluteFile());
        BufferedWriter bw = new BufferedWriter(fw);
        bw.write(newIndex);
        bw.close();
        return newIndex;
    }

    private Object getJSONFromEntity(Entity entity, TreeMap<String, Integer> depthObj) throws JSONException {

        JSONObject jsonObject = new JSONObject();
        JSONArray jsa = null;
        String name = entity.getName();
        if (name.startsWith(JAVA_UTIL_HASH_MAP) ||
        name.startsWith(JAVA_UTIL_MAP) ||
        name.startsWith(JAVA_UTIL_SET) ||
        name.startsWith(JAVA_UTIL_LIST) ||
        name.startsWith(JAVA_UTIL_COLLECTION)) {
            jsa = new JSONArray();
            final int beginIndex = name.indexOf("<") + 1;
            final int finishIndex = name.indexOf(">");
            name = name.substring(beginIndex, finishIndex);
        }

        if (name.endsWith("[]")) {
            jsa = new JSONArray();
            name = name.substring(0, name.length() - 2);
        }

        final AbstractEntity extractEntity = extractEntity(name);
        if (null != extractEntity) {
            if (!depthObj.containsKey(name)) {
                depthObj.put(name, 0);
            }
            final Integer nbObj = depthObj.get(name) + 1;
            if (nbObj > MAX_DEPTH) {
                return new JSONObject();
            }
            depthObj.put(name, nbObj);
            // SuperClass
            final Entity myEntity = (Entity) extractEntity;
            final String superClass = myEntity.getSuperClass();
            if (null != superClass) {
                final AbstractEntity superClassEntity = extractEntity(superClass);
                if (null != superClassEntity) {
                    jsonObject = enrichWithFields(jsonObject, (Entity) superClassEntity, depthObj);
                }
            }
            // Class
            jsonObject = enrichWithFields(jsonObject, myEntity, depthObj);
        } else {
            // Maybe a JDK type, trying to instanciate
            Class aClass = null;
            if ("java.lang.Double".equals(name)) {
                name = "double";
            } else if ("java.lang.Integer".equals(name)) {
                name = "int";
            } else if ("java.lang.Boolean".equals(name)) {
                name = "boolean";
            } else if ("java.lang.Byte".equals(name)) {
                name = "byte";
            } else if ("java.lang.Short".equals(name)) {
                name = "short";
            } else if ("java.lang.Long".equals(name)) {
                name = "long";
            } else if ("java.lang.Float".equals(name)) {
                name = "float";
            }

            try {
                aClass = Class.forName(name);
            } catch (ClassNotFoundException e) {
                if ("boolean".equals(name)) {
                    aClass = boolean.class;
                } else if ("byte".equals(name)) {
                    aClass = byte.class;
                } else if ("short".equals(name)) {
                    aClass = short.class;
                } else if ("int".equals(name)) {
                    aClass = int.class;
                } else if ("long".equals(name)) {
                    aClass = long.class;
                } else if ("double".equals(name)) {
                    aClass = double.class;
                } else if ("float".equals(name)) {
                    aClass = float.class;
                }
            }
            try {
                return aClass.newInstance();
            } catch (Exception e) {
                return defaultValue(aClass);
            }
        }
        if (null != jsa) {
            jsa.put(jsonObject);
            return jsa;
        }
        return jsonObject;
    }

    private AbstractEntity extractEntity(String entityName) {
        for (AbstractEntity entity : entities) {
            if (entityName.equals(entity.getName())) {
                return entity;
            }
        }
        return null;
    }

    private JSONObject enrichWithFields(JSONObject parent, Entity entity, TreeMap<String, Integer> depthObj)
    throws JSONException {
        final Map<String, AbstractEntity> fields = entity.getFields();
        if (fields != null && fields.keySet() != null) {
            final Iterator<String> iterator = fields.keySet().iterator();
            while (iterator.hasNext()) {
                final String key = iterator.next();
                final AbstractEntity abstractEntity = fields.get(key);
                if (null != abstractEntity) {
                    if (abstractEntity instanceof Entity) {
                        if (depthObj.containsKey(abstractEntity.getName()) && depthObj.get(abstractEntity.getName()) > MAX_DEPTH) {
                            parent.put(key, new JSONObject());
                        } else {
                            parent.put(key, getJSONFromEntity((Entity) abstractEntity, depthObj));
                        }
                    } else {
                        final AbstractEntity enumExtracted = extractEntity(abstractEntity.getName());
                        if (null != enumExtracted) {
                            final List<String> values = ((Enumeration) enumExtracted).getValues();
                            if (values != null && !values.isEmpty()) {
                                parent.put(key, values.get(0));
                            }
                        } else {
                            parent.put(key, "{" + abstractEntity.getName() + "}");
                        }
                        // }
                    }
                }
            }
        }
        return parent;
    }

    private CharSequence decorateURL(String context) {
        String url = context.replaceAll("\\{", "<span class=\"ink-label blue invert\">{")
        .replaceAll("}", "}</span>")
        .replaceAll("&", "</span>&<span class=\"ink-label green invert\">")
        .replaceAll("\\?", "?<span class=\"ink-label green invert\">");
        if (url.indexOf("?") > -1) {
            url = url + "</span>";
        }
        return url;
    }

    private CharSequence extractNameFromFQN(String name) {
        final String[] split = name.split("<");
        boolean first = true;
        StringBuilder sb = new StringBuilder();
        for (String part : split) {
            sb.append(part.substring(part.lastIndexOf(DOT) + 1));
            if (first && split.length > 1) {
                sb.append("<");
            }
            first = false;
        }
        return sb.toString();
    }
}
