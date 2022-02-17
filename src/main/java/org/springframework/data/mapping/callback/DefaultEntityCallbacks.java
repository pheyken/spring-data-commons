/*
 * Copyright 2019-2022 the original author or authors.
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
package org.springframework.data.mapping.callback;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.BiFunction;

import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.ResolvableType;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ReflectionUtils;

/**
 * {@link EntityCallbacks} implementation using an {@link EntityCallbackDiscoverer} to retrieve {@link EntityCallback
 * EntityCallbacks} from a {@link BeanFactory}.
 *
 * @author Mark Paluch
 * @author Christoph Strobl
 * @since 2.2
 */
class DefaultEntityCallbacks implements EntityCallbacks {

	private final Map<Class<?>, Method> callbackMethodCache = new ConcurrentReferenceHashMap<>(64);
	private final SimpleEntityCallbackInvoker callbackInvoker = new SimpleEntityCallbackInvoker();
	private final EntityCallbackDiscoverer callbackDiscoverer;

	/**
	 * Create new instance of {@link DefaultEntityCallbacks}.
	 */
	DefaultEntityCallbacks() {
		this.callbackDiscoverer = new EntityCallbackDiscoverer();
	}

	/**
	 * Create new instance of {@link DefaultEntityCallbacks} discovering {@link EntityCallback entity callbacks} within
	 * the given {@link BeanFactory}.
	 *
	 * @param beanFactory must not be {@literal null}.
	 */
	DefaultEntityCallbacks(BeanFactory beanFactory) {
		this.callbackDiscoverer = new EntityCallbackDiscoverer(beanFactory);
	}

	@Override
	public <T> T callback(Class<? extends EntityCallback> callbackType, T entity, Object... args) {

		Assert.notNull(entity, "Entity must not be null!");

		var entityType = (Class<T>) (entity != null ? ClassUtils.getUserClass(entity.getClass())
				: callbackDiscoverer.resolveDeclaredEntityType(callbackType).getRawClass());

		var callbackMethod = callbackMethodCache.computeIfAbsent(callbackType, it -> {

			var method = EntityCallbackDiscoverer.lookupCallbackMethod(it, entityType, args);
			ReflectionUtils.makeAccessible(method);
			return method;
		});

		var value = entity;

		for (var callback : callbackDiscoverer.getEntityCallbacks(entityType,
				ResolvableType.forClass(callbackType))) {

			var callbackFunction = EntityCallbackDiscoverer
					.computeCallbackInvokerFunction(callback, callbackMethod, args);
			value = callbackInvoker.invokeCallback(callback, value, callbackFunction);
		}

		return value;
	}

	@Override
	public void addEntityCallback(EntityCallback<?> callback) {
		this.callbackDiscoverer.addEntityCallback(callback);
	}

	static class SimpleEntityCallbackInvoker implements org.springframework.data.mapping.callback.EntityCallbackInvoker {

		@Override
		public <T> T invokeCallback(EntityCallback<T> callback, T entity,
				BiFunction<EntityCallback<T>, T, Object> callbackInvokerFunction) {

			try {

				var value = callbackInvokerFunction.apply(callback, entity);

				if (value != null) {
					return (T) value;
				}

				throw new IllegalArgumentException(
						String.format("Callback invocation on %s returned null value for %s", callback.getClass(), entity));

			} catch (ClassCastException ex) {

				var msg = ex.getMessage();
				if (msg == null || EntityCallbackInvoker.matchesClassCastMessage(msg, entity.getClass())) {

					// Possibly a lambda-defined listener which we could not resolve the generic event type for
					// -> let's suppress the exception and just log a debug message.
					var logger = LogFactory.getLog(getClass());
					if (logger.isDebugEnabled()) {
						logger.debug("Non-matching callback type for entity callback: " + callback, ex);
					}
					return entity;
				} else {
					throw ex;
				}
			}
		}
	}
}
