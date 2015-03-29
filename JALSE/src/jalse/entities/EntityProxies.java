package jalse.entities;

import static jalse.misc.JALSEExceptions.INVALID_ENTITY_TYPE;
import static jalse.misc.JALSEExceptions.throwRE;
import static jalse.misc.TypeParameterResolver.getTypeParameter;
import static jalse.misc.TypeParameterResolver.toClass;
import jalse.attributes.AttributeType;
import jalse.attributes.Attributes;
import jalse.entities.annotations.GetAttribute;
import jalse.entities.annotations.GetEntities;
import jalse.entities.annotations.GetEntity;
import jalse.entities.annotations.NewEntity;
import jalse.entities.annotations.SetAttribute;
import jalse.entities.annotations.StreamEntities;
import jalse.misc.ProxyCache;
import jalse.misc.ProxyCache.CacheHandler;
import jalse.misc.ProxyCache.ProxyFactory;
import jalse.misc.TypeParameterResolver;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * A utility for validating {@link Entity} subclasses and creating proxies of these. The proxies are
 * cached for performance reasons but can be managed using this utility. It is possible to create a
 * new uncached proxy instance with {@link #uncachedProxyOfEntity(Entity, Class)}.<br>
 * <br>
 * An entity subclass can have the following method definitions: <br>
 * 1) Setting an Attribute of type (will remove if argument passed is null).<br>
 * 2) Getting an Attribute of type. <br>
 * 3) Getting an Entity as type. <br>
 * 4) Creating a new Entity of type. <br>
 * 5) Getting a Set of all of the child entities of or as type.<br>
 * 6) Getting a Stream of all of the child entities of or as type.<br>
 * <br>
 * For an Entity proxy to be created the type be validated: <br>
 * 2. All attributes types must not be primitives (can be null).<br>
 * 1. Can only have super types that are (or are subclasses of) Entity. <br>
 * 3. Must only contain default or annotated methods:<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;a) {@link SetAttribute} <br>
 * &nbsp;&nbsp;&nbsp;&nbsp;b) {@link GetAttribute} <br>
 * &nbsp;&nbsp;&nbsp;&nbsp;c) {@link GetEntity} <br>
 * &nbsp;&nbsp;&nbsp;&nbsp;d) {@link NewEntity}<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;e) {@link GetEntities}<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;f) {@link StreamEntities}<br>
 * <br>
 * NOTE: The javadoc for the annotations provide more information about how to define the method.<br>
 * <br>
 * An example entity type:
 *
 * <pre>
 * <code>
 * public interface Ghost extends Entity {
 * 
 * 	{@code @GetAttribute("scary")}
 * 	Optional{@code<Boolean>} isScary();
 * 
 * 	{@code @SetAttribute("scary")}
 * 	void setScary(Boolean scary);
 * }
 * 
 * Entity e; // Previously created entity
 * 
 * Ghost evilGhost = Entities.asType(e, Ghost.class);
 * if (evilGhost.isScary()) {
 * 	// AAAAAH!
 * }
 * </code>
 * </pre>
 *
 * @author Elliot Ford
 *
 * @see #proxyOfEntity(Entity, Class)
 * @see ProxyCache
 *
 */
public final class EntityProxies {

    private static class EntityTypeFactory implements ProxyFactory {

	@Override
	public CacheHandler createHandler(final Object obj, final Class<?> type) {
	    return new EntityTypeHandler(obj, type);
	}

	@Override
	public boolean validate(final Class<?> type) {
	    return validateTree(type, new HashSet<>());
	}
    }

    private static class EntityTypeHandler extends CacheHandler {

	public EntityTypeHandler(final Object obj, final Class<?> type) {
	    super(obj, type);
	}

	@SuppressWarnings("unchecked")
	@Override
	public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
	    final Entity entity = (Entity) getOwner();
	    if (entity == null) {
		throw new IllegalStateException("Entity reference lost");
	    }

	    /*
	     * Not Entity subclass.
	     */
	    final Class<?> declaringClazz = method.getDeclaringClass();
	    if (Entity.class.equals(declaringClazz) || !Entity.class.isAssignableFrom(declaringClazz)) {
		return method.invoke(entity, args);
	    }

	    /*
	     * Default methods.
	     */
	    if (method.isDefault()) {
		return lookups.get(declaringClazz).unreflectSpecial(method, declaringClazz).bindTo(proxy)
			.invokeWithArguments(args);
	    }

	    /*
	     * Entity type method info.
	     */
	    final EntityTypeMethodInfo etmi = methodInfos.get(method);

	    /*
	     * getAttributeOfType(Class) / getOrNullAttributeOfType(Class)
	     */
	    if (etmi.forAnnotationType(GetAttribute.class)) {
		if (etmi.orNull) {
		    return entity.getOrNullAttributeOfType(etmi.type);
		} else {
		    return entity.getAttributeOfType(etmi.type);
		}
	    }

	    /*
	     * addAttributeOfType(Attribute) / addOrNullAttributeOfType(Attribute) /
	     * removeAttributeOfType(Class) / removeOrNullAttributeOfType(Class)
	     */
	    if (etmi.forAnnotationType(SetAttribute.class)) {
		if (etmi.orNull) {
		    if (args[0] != null) {
			return entity.addOrNullAttributeOfType((AttributeType<Object>) etmi.type, args[0]);
		    } else {
			return entity.removeOrNullAttributeOfType(etmi.type);
		    }
		} else {
		    if (args[0] != null) {
			return entity.addAttributeOfType((AttributeType<Object>) etmi.type, args[0]);
		    } else {
			return entity.removeAttributeOfType(etmi.type);
		    }
		}
	    }

	    /*
	     * newEntity(Class) / newEntity(UUID, Class)
	     */
	    if (etmi.forAnnotationType(NewEntity.class)) {
		if (args != null && args.length == 1) {
		    return entity.newEntity((UUID) args[0], (Class<? extends Entity>) etmi.resolved);
		} else {
		    return entity.newEntity((Class<? extends Entity>) etmi.resolved);
		}
	    }

	    /*
	     * getEntityAsType(Class)
	     */
	    if (etmi.forAnnotationType(GetEntity.class)) {
		if (etmi.orNull) {
		    return entity.getOrNullEntityAsType((UUID) args[0], (Class<? extends Entity>) etmi.resolved);
		} else {
		    return entity.getEntityAsType((UUID) args[0], (Class<? extends Entity>) etmi.resolved);
		}
	    }

	    /*
	     * streamEntitiesOfType(Class) / streamEntitiesAsType(Class)
	     */
	    if (etmi.forAnnotationType(StreamEntities.class)) {
		if (((StreamEntities) etmi.annotation).ofType()) {
		    return entity.streamEntitiesOfType((Class<? extends Entity>) etmi.resolved);
		} else {
		    return entity.streamEntitiesAsType((Class<? extends Entity>) etmi.resolved);
		}
	    }

	    /*
	     * getEntitiesOfType(Class) / getEntitiesAsType(Class)
	     */
	    if (etmi.forAnnotationType(GetEntities.class)) {
		if (((GetEntities) etmi.annotation).ofType()) {
		    return entity.getEntitiesOfType((Class<? extends Entity>) etmi.resolved);
		} else {
		    return entity.getEntitiesAsType((Class<? extends Entity>) etmi.resolved);
		}
	    }

	    throw new UnsupportedOperationException();
	}
    }

    private static class EntityTypeMethodInfo {

	private final Annotation annotation;
	private final Class<?> resolved;
	private boolean orNull;
	private AttributeType<?> type;

	private EntityTypeMethodInfo(final Annotation annotation, final Class<?> resolved) {
	    this.annotation = annotation;
	    this.resolved = resolved;
	    orNull = false;
	    type = null;
	}

	public boolean forAnnotationType(final Class<? extends Annotation> type) {
	    return annotation.annotationType().equals(type);
	}
    }

    private static final TypeParameterResolver OPTIONAL_RESOLVER = new TypeParameterResolver(getTypeParameter(
	    Optional.class, "T"));

    private static final TypeParameterResolver SET_RESOLVER = new TypeParameterResolver(
	    getTypeParameter(Set.class, "E"));

    private static final TypeParameterResolver STREAM_RESOLVER = new TypeParameterResolver(getTypeParameter(
	    Stream.class, "T"));

    private static List<Class<? extends Annotation>> ANNOTATIONS = Arrays.asList(GetAttribute.class,
	    SetAttribute.class, StreamEntities.class, GetEntities.class, GetEntity.class, NewEntity.class);

    private static final ProxyCache typeCache = new ProxyCache(new EntityTypeFactory());

    private static final ConcurrentMap<Class<?>, Lookup> lookups = new ConcurrentHashMap<>();

    private static final ConcurrentMap<Method, EntityTypeMethodInfo> methodInfos = new ConcurrentHashMap<>();

    /**
     * Checks to see if the supplied entity is a proxy.
     *
     * @param e
     *            Possible proxy to check.
     * @return Whether the entity is a proxy.
     */
    public static boolean isProxyEntity(final Entity e) {
	return Proxy.isProxyClass(e.getClass()) && Proxy.getInvocationHandler(e) instanceof EntityTypeHandler;
    }

    private static Lookup newLookupInstance(final Class<?> type) {
	try {
	    final Constructor<Lookup> constructor = Lookup.class.getDeclaredConstructor(Class.class, int.class);
	    constructor.setAccessible(true);
	    return constructor.newInstance(type, Lookup.PRIVATE);
	} catch (final Exception e) {
	    throw new RuntimeException(String.format("Private lookup needed of %s for default method access", type), e);
	}
    }

    /**
     * Gets or creates a proxy of the supplied entity as the suppied type.
     *
     * @param e
     *            Entity to wrap.
     * @param type
     *            Entity type.
     * @return Entity proxy of type.
     *
     * @see #validateEntityType(Class)
     */
    public static <T extends Entity> T proxyOfEntity(final Entity e, final Class<T> type) {
	validateEntityType(type);
	return typeCache.getOrCreate(e, type);
    }

    /**
     * Removes all proxies in the cache.
     */
    public static void removeAllProxies() {
	typeCache.removeAll();
    }

    /**
     * Removes all proxies of the supplied type.
     *
     * @param type
     *            Entity type.
     */
    public static void removeAllProxiesOfType(final Class<? extends Entity> type) {
	typeCache.invalidateType(type);
    }

    /**
     * Removes all proxies of a supplied entity.
     *
     * @param e
     *            Entity to remove proxies for.
     */
    public static void removeProxiesOfEntity(final Entity e) {
	typeCache.removeAll(e);
    }

    /**
     * Removes a specific proxy of an entity.
     *
     * @param e
     *            Entity remove for.
     * @param type
     *            Entity type.
     */
    public static void removeProxyOfEntity(final Entity e, final Class<? extends Entity> type) {
	typeCache.remove(e, type);
    }

    /**
     * Creates a new uncached proxy of an entity as the supplied type.
     *
     * @param e
     *            Entity to wrap.
     * @param type
     *            Entity type.
     * @return New uncached proxy instance of entity.
     */
    @SuppressWarnings("unchecked")
    public static <T extends Entity> T uncachedProxyOfEntity(final Entity e, final Class<T> type) {
	if (!validateTree(type, new HashSet<>())) {
	    throwRE(INVALID_ENTITY_TYPE);
	}
	return (T) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[] { type },
		new EntityTypeHandler(e, type));
    }

    /**
     * Validates a specified Entity type according the criteria defined above. The ancestor
     * {@code interface} {@link Entities} is considered to be invalid.
     *
     * @param type
     *            Entity type to validate.
     * @throws IllegalArgumentException
     *             If the Entity type fails validation.
     */
    public static void validateEntityType(final Class<? extends Entity> type) {
	if (Entity.class.equals(type) || !typeCache.validateType(type)) {
	    throwRE(INVALID_ENTITY_TYPE);
	}
    }

    private static boolean validateTree(final Class<?> type, final Set<Class<?>> validated) {
	if (!Entity.class.isAssignableFrom(type)) { // Not Entity or subclass.
	    return false;
	} else if (Entity.class.equals(type)) { // Can stop here.
	    return true;
	}

	final Map<Method, EntityTypeMethodInfo> resolvedMethods = new HashMap<>();
	final Set<Class<?>> referencedtypes = new HashSet<>();

	for (final Method method : type.getDeclaredMethods()) {
	    /*
	     * Handler annotation.
	     */
	    Annotation annotation = null;
	    for (final Annotation a : method.getAnnotations()) {
		if (ANNOTATIONS.contains(a.annotationType())) {
		    if (annotation != null) {
			return false;
		    }
		    annotation = a;
		}
	    }

	    final boolean isDefaultOrStatic = method.isDefault() || Modifier.isStatic(method.getModifiers());

	    /*
	     * Skip un-annotated default methods.
	     */
	    if (annotation == null && isDefaultOrStatic) {
		continue;
	    } else if (annotation == null || isDefaultOrStatic) {
		return false;
	    }

	    /*
	     * Method info.
	     */
	    final Type[] params = method.getParameterTypes();
	    final boolean hasParams = params != null && params.length > 0;
	    final Type returnType = method.getGenericReturnType();
	    final boolean hasReturnType = !Void.TYPE.equals(returnType);

	    /*
	     * getAttributeOfType(Class) / getOrNullAttributeOfType(Class)
	     */
	    if (GetAttribute.class.equals(annotation.annotationType())) {
		final String name = ((GetAttribute) annotation).value();
		if (!hasReturnType || hasParams || name.length() == 0) {
		    return false;
		}

		Class<?> resolved = toClass(returnType);
		boolean orNull = true;

		if (Optional.class.equals(resolved)) {
		    resolved = toClass(OPTIONAL_RESOLVER.resolve(returnType));
		    orNull = false;
		}

		if (resolved.isPrimitive()) {
		    return false;
		}

		final EntityTypeMethodInfo etmi = new EntityTypeMethodInfo(annotation, resolved);
		etmi.orNull = orNull;
		etmi.type = Attributes.newType(name, resolved);

		resolvedMethods.put(method, etmi);
		continue;
	    }

	    /*
	     * addAttributeOfType(Attribute) / addOrNullAttributeOfType(Attribute) /
	     * removeAttributeOfType(Class) / removeOrNullAttributeOfType(Class)
	     */
	    if (SetAttribute.class.equals(annotation.annotationType())) {
		final String name = ((SetAttribute) annotation).value();
		if (!hasParams || params.length != 1 || name.length() == 0) {
		    return false;
		}

		final Class<?> resolved = toClass(params[0]);
		boolean orNull = true;

		if (resolved.isPrimitive()) {
		    return false;
		}

		if (hasReturnType) {
		    Class<?> returnTypeClazz = toClass(returnType);

		    if (Optional.class.equals(returnTypeClazz)) {
			returnTypeClazz = toClass(OPTIONAL_RESOLVER.resolve(returnType));
			orNull = false;
		    }

		    if (!resolved.equals(returnTypeClazz)) {
			return false;
		    }
		}

		final EntityTypeMethodInfo etmi = new EntityTypeMethodInfo(annotation, resolved);
		etmi.orNull = orNull;
		etmi.type = Attributes.newType(name, resolved);

		resolvedMethods.put(method, etmi);
		continue;
	    }

	    /*
	     * newEntity(Class) / newEntity(UUID, Class)
	     */
	    if (NewEntity.class.equals(annotation.annotationType())) {
		if (!hasReturnType || !(!hasParams || hasParams && params.length == 1 && UUID.class.equals(params[0]))) {
		    return false;
		}
		final Class<?> resolved = toClass(returnType);
		if (!resolved.isInterface() || Entity.class.equals(resolved)
			|| !Entity.class.isAssignableFrom(resolved)) {
		    return false;
		}

		resolvedMethods.put(method, new EntityTypeMethodInfo(annotation, resolved));
		referencedtypes.add(resolved);
		continue;
	    }

	    /*
	     * getEntityAsType(Class)
	     */
	    if (GetEntity.class.equals(annotation.annotationType())) {
		if (!hasReturnType || !hasParams || params.length != 1 || !UUID.class.equals(params[0])) {
		    return false;
		}
		Class<?> resolved = toClass(returnType);
		boolean orNull = true;

		if (Optional.class.equals(resolved)) {
		    resolved = toClass(OPTIONAL_RESOLVER.resolve(returnType));
		    orNull = false;
		}

		if (!resolved.isInterface() || Entity.class.equals(resolved)
			|| !Entity.class.isAssignableFrom(resolved)) {
		    return false;
		}

		final EntityTypeMethodInfo etmi = new EntityTypeMethodInfo(annotation, resolved);
		etmi.orNull = orNull;

		resolvedMethods.put(method, etmi);
		referencedtypes.add(resolved);
		continue;
	    }

	    /*
	     * streamEntitiesOfType(Class) / streamEntitiesAsType(Class)
	     */
	    if (StreamEntities.class.equals(annotation.annotationType())) {
		if (!hasReturnType || hasParams) {
		    return false;
		}

		final Class<?> returnTypeClazz = toClass(returnType);

		if (!Stream.class.equals(returnTypeClazz)) {
		    return false;
		}

		final Class<?> resolved = toClass(STREAM_RESOLVER.resolve(returnType));

		if (!resolved.isInterface() || Entity.class.equals(resolved)
			|| !Entity.class.isAssignableFrom(resolved)) {
		    return false;
		}

		resolvedMethods.put(method, new EntityTypeMethodInfo(annotation, resolved));
		referencedtypes.add(resolved);
		continue;
	    }

	    /*
	     * getEntitiesOfType(Class) / getEntitiesAsType(Class)
	     */
	    if (GetEntities.class.equals(annotation.annotationType())) {
		if (!hasReturnType || hasParams) {
		    return false;
		}

		final Class<?> returnTypeClazz = toClass(returnType);

		if (!Set.class.equals(returnTypeClazz)) {
		    return false;
		}

		final Class<?> resolved = toClass(SET_RESOLVER.resolve(returnType));

		if (!resolved.isInterface() || Entity.class.equals(resolved)
			|| !Entity.class.isAssignableFrom(resolved)) {
		    return false;
		}

		resolvedMethods.put(method, new EntityTypeMethodInfo(annotation, resolved));
		referencedtypes.add(resolved);
		continue;
	    }

	    /*
	     * If not found above then it is not a valid Entity type method.
	     */
	    return false;
	}

	validated.add(type); // Avoid cyclic dependencies..

	referencedtypes.addAll(Arrays.asList(type.getInterfaces())); // Direct super types.

	/*
	 * Validate referenced types.
	 */
	for (final Class<?> ref : referencedtypes) {
	    if (validated.contains(ref)) {
		continue;
	    }

	    if (!validateTree(ref, validated)) {
		return false;
	    }
	}

	lookups.computeIfAbsent(type, EntityProxies::newLookupInstance);
	methodInfos.putAll(resolvedMethods);

	return true;
    }

    private EntityProxies() {
	throw new UnsupportedOperationException();
    }
}