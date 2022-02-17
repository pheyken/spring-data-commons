/*
 * Copyright 2008-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.repository.query;

import static java.lang.String.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.util.ClassUtils;
import org.springframework.data.repository.util.QueryExecutionConverters;
import org.springframework.data.repository.util.ReactiveWrapperConverters;
import org.springframework.data.util.ClassTypeInformation;
import org.springframework.data.util.Lazy;
import org.springframework.util.Assert;

/**
 * Class to abstract a single parameter of a query method. It is held in the context of a {@link Parameters} instance.
 *
 * @author Oliver Gierke
 * @author Mark Paluch
 * @author Jens Schauder
 */
public class Parameter {

	static final List<Class<?>> TYPES;

	private static final String NAMED_PARAMETER_TEMPLATE = ":%s";
	private static final String POSITION_PARAMETER_TEMPLATE = "?%s";

	private final MethodParameter parameter;
	private final Class<?> parameterType;
	private final boolean isDynamicProjectionParameter;
	private final Lazy<Optional<String>> name;

	static {

		List<Class<?>> types = new ArrayList<>(Arrays.asList(Pageable.class, Sort.class));

		// consider Kotlin Coroutines Continuation a special parameter. That parameter is synthetic and should not get
		// bound to any query.

		ClassUtils.ifPresent("kotlin.coroutines.Continuation", Parameter.class.getClassLoader(), types::add);

		TYPES = Collections.unmodifiableList(types);
	}

	/**
	 * Creates a new {@link Parameter} for the given {@link MethodParameter}.
	 *
	 * @param parameter must not be {@literal null}.
	 */
	protected Parameter(MethodParameter parameter) {

		Assert.notNull(parameter, "MethodParameter must not be null!");

		this.parameter = parameter;
		this.parameterType = potentiallyUnwrapParameterType(parameter);
		this.isDynamicProjectionParameter = isDynamicProjectionParameter(parameter);
		this.name = TYPES.contains(parameter.getParameterType()) ? Lazy.of(Optional.empty()) : Lazy.of(() -> {
			var annotation = parameter.getParameterAnnotation(Param.class);
			return Optional.ofNullable(annotation == null ? parameter.getParameterName() : annotation.value());
		});
	}

	/**
	 * Returns whether the parameter is a special parameter.
	 *
	 * @return
	 * @see #TYPES
	 */
	public boolean isSpecialParameter() {
		return isDynamicProjectionParameter || TYPES.contains(parameter.getParameterType());
	}

	/**
	 * Returns whether the {@link Parameter} is to be bound to a query.
	 *
	 * @return
	 */
	public boolean isBindable() {
		return !isSpecialParameter();
	}

	/**
	 * Returns whether the current {@link Parameter} is the one used for dynamic projections.
	 *
	 * @return
	 */
	public boolean isDynamicProjectionParameter() {
		return isDynamicProjectionParameter;
	}

	/**
	 * Returns the placeholder to be used for the parameter. Can either be a named one or positional.
	 *
	 * @return
	 */
	public String getPlaceholder() {

		if (isNamedParameter()) {
			return format(NAMED_PARAMETER_TEMPLATE, getName().get());
		} else {
			return format(POSITION_PARAMETER_TEMPLATE, getIndex());
		}
	}

	/**
	 * Returns the position index the parameter is bound to in the context of its surrounding {@link Parameters}.
	 *
	 * @return
	 */
	public int getIndex() {
		return parameter.getParameterIndex();
	}

	/**
	 * Returns whether the parameter is annotated with {@link Param}.
	 *
	 * @return
	 */
	public boolean isNamedParameter() {
		return !isSpecialParameter() && getName().isPresent();
	}

	/**
	 * Returns the name of the parameter (through {@link Param} annotation).
	 *
	 * @return
	 */
	public Optional<String> getName() {
		return this.name.get();
	}

	/**
	 * Returns the type of the {@link Parameter}.
	 *
	 * @return the type
	 */
	public Class<?> getType() {
		return parameterType;
	}

	/**
	 * Returns whether the parameter is named explicitly, i.e. annotated with {@link Param}.
	 *
	 * @return
	 * @since 1.11
	 */
	public boolean isExplicitlyNamed() {
		return parameter.hasParameterAnnotation(Param.class);
	}

	@Override
	public String toString() {
		return format("%s:%s", isNamedParameter() ? getName() : "#" + getIndex(), getType().getName());
	}

	/**
	 * Returns whether the {@link Parameter} is a {@link Pageable} parameter.
	 *
	 * @return
	 */
	boolean isPageable() {
		return Pageable.class.isAssignableFrom(getType());
	}

	/**
	 * Returns whether the {@link Parameter} is a {@link Sort} parameter.
	 *
	 * @return
	 */
	boolean isSort() {
		return Sort.class.isAssignableFrom(getType());
	}

	/**
	 * Returns whether the given {@link MethodParameter} is a dynamic projection parameter, which means it carries a
	 * dynamic type parameter which is identical to the type parameter of the actually returned type.
	 * <p>
	 * <code>
	 * <T> Collection<T> findBy…(…, Class<T> type);
	 * </code>
	 *
	 * @param parameter must not be {@literal null}.
	 * @return
	 */
	private static boolean isDynamicProjectionParameter(MethodParameter parameter) {

		var method = parameter.getMethod();

		if (method == null) {
			throw new IllegalStateException(String.format("Method parameter %s is not backed by a method!", parameter));
		}

		var ownerType = ClassTypeInformation.from(parameter.getDeclaringClass());
		var parameterTypes = ownerType.getParameterTypes(method).get(parameter.getParameterIndex());

		if (!parameterTypes.getType().equals(Class.class)) {
			return false;
		}

		var bound = parameterTypes.getTypeArguments().get(0);
		var returnType = ClassTypeInformation.fromReturnTypeOf(method);

		return bound
				.equals(QueryExecutionConverters.unwrapWrapperTypes(ReactiveWrapperConverters.unwrapWrapperTypes(returnType)));
	}

	/**
	 * Returns whether the {@link MethodParameter} is wrapped in a wrapper type.
	 *
	 * @param parameter must not be {@literal null}.
	 * @return
	 * @see QueryExecutionConverters
	 */
	private static boolean isWrapped(MethodParameter parameter) {
		return QueryExecutionConverters.supports(parameter.getParameterType())
				|| ReactiveWrapperConverters.supports(parameter.getParameterType());
	}

	/**
	 * Returns whether the {@link MethodParameter} should be unwrapped.
	 *
	 * @param parameter must not be {@literal null}.
	 * @return
	 * @see QueryExecutionConverters
	 */
	private static boolean shouldUnwrap(MethodParameter parameter) {
		return QueryExecutionConverters.supportsUnwrapping(parameter.getParameterType());
	}

	/**
	 * Returns the component type if the given {@link MethodParameter} is a wrapper type and the wrapper should be
	 * unwrapped.
	 *
	 * @param parameter must not be {@literal null}.
	 * @return
	 */
	private static Class<?> potentiallyUnwrapParameterType(MethodParameter parameter) {

		var originalType = parameter.getParameterType();

		if (isWrapped(parameter) && shouldUnwrap(parameter)) {
			return ResolvableType.forMethodParameter(parameter).getGeneric(0).resolve(Object.class);
		}

		return originalType;
	}
}
