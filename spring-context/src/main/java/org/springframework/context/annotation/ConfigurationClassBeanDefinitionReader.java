/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.context.annotation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.RequiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.groovy.GroovyBeanDefinitionReader;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Reads a given fully-populated set of ConfigurationClass instances, registering bean
 * definitions with the given {@link BeanDefinitionRegistry} based on its contents.
 *
 * <p>This class was modeled after the {@link BeanDefinitionReader} hierarchy, but does
 * not implement/extend any of its artifacts as a set of configuration classes is not a
 * {@link Resource}.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 3.0
 * @see ConfigurationClassParser
 */
class ConfigurationClassBeanDefinitionReader {

	private static final Log logger = LogFactory.getLog(ConfigurationClassBeanDefinitionReader.class);

	private static final ScopeMetadataResolver scopeMetadataResolver = new AnnotationScopeMetadataResolver();

	private final BeanDefinitionRegistry registry;

	private final SourceExtractor sourceExtractor;

	private final ResourceLoader resourceLoader;

	private final Environment environment;

	private final BeanNameGenerator importBeanNameGenerator;

	private final ImportRegistry importRegistry;

	private final ConditionEvaluator conditionEvaluator;


	/**
	 * Create a new {@link ConfigurationClassBeanDefinitionReader} instance
	 * that will be used to populate the given {@link BeanDefinitionRegistry}.
	 */
	ConfigurationClassBeanDefinitionReader(BeanDefinitionRegistry registry, SourceExtractor sourceExtractor,
			ResourceLoader resourceLoader, Environment environment, BeanNameGenerator importBeanNameGenerator,
			ImportRegistry importRegistry) {

		this.registry = registry;
		this.sourceExtractor = sourceExtractor;
		this.resourceLoader = resourceLoader;
		this.environment = environment;
		this.importBeanNameGenerator = importBeanNameGenerator;
		this.importRegistry = importRegistry;
		this.conditionEvaluator = new ConditionEvaluator(registry, environment, resourceLoader);
	}


	/**
	 * 对加载的配置类中待处理的配置进行解析
	 *
	 * Read {@code configurationModel}, registering bean definitions
	 * with the registry based on its contents.
	 */
	public void loadBeanDefinitions(Set<ConfigurationClass> configurationModel) {
		// 构建条件解析对象
		TrackedConditionEvaluator trackedConditionEvaluator = new TrackedConditionEvaluator();
		// 遍历配置类将待处理的配置处理了
		for (ConfigurationClass configClass : configurationModel) {
			loadBeanDefinitionsForConfigurationClass(configClass, trackedConditionEvaluator);
		}
	}

	/**
	 * 处理配置类中待加入容器中的配置
	 * 其中包括：
	 * 		1、@Import注解中的普通类和实现ImportSelector接口方法返回的类名(通过这些类名加载的类)
	 * 		2、@Import注解中实现了ImportBeanDefinitionRegistrar接口的类
	 * 		3、@ImportResource注解
	 * 		4、含有@Bean注解的方法
	 *
	 * Read a particular {@link ConfigurationClass}, registering bean definitions
	 * for the class itself and all of its {@link Bean} methods.
	 */
	private void loadBeanDefinitionsForConfigurationClass(
			ConfigurationClass configClass, TrackedConditionEvaluator trackedConditionEvaluator) {

		/*
		 * 判断配置类是否符合加载条件，如果返回true的话就证明不符合加载条件
		 * 这时需要将环境中关于此配置的一些信息删除掉
		 */
		if (trackedConditionEvaluator.shouldSkip(configClass)) {
			String beanName = configClass.getBeanName();
			if (StringUtils.hasLength(beanName) && this.registry.containsBeanDefinition(beanName)) {
				this.registry.removeBeanDefinition(beanName);
			}
			this.importRegistry.removeImportingClass(configClass.getMetadata().getClassName());
			return;
		}

		// 如果此类是被其他类导入的，则将其注册进容器中
		if (configClass.isImported()) {
			registerBeanDefinitionForImportedConfigurationClass(configClass);
		}
		// 解析 @Bean 方法，将返回的对象注册进容器中
		for (BeanMethod beanMethod : configClass.getBeanMethods()) {
			loadBeanDefinitionsForBeanMethod(beanMethod);
		}

		// 解析 @ImportResource 注解，就是将XML配置文件中的Bean加载到容器中(暂不研究)
		loadBeanDefinitionsFromImportedResources(configClass.getImportedResources());
		// 解析 @Import 注解中实现了ImportBeanDefinitionRegistrar接口的类
		loadBeanDefinitionsFromRegistrars(configClass.getImportBeanDefinitionRegistrars());
	}

	/**
	 * 将被导入的配置类转化成 BeanDefinition 注册进容器中
	 *
	 * Register the {@link Configuration} class itself as a bean definition.
	 */
	private void registerBeanDefinitionForImportedConfigurationClass(ConfigurationClass configClass) {
		// 获取配置类的元信息并通过构造函数创建一个 BeanDefinition
		AnnotationMetadata metadata = configClass.getMetadata();
		AnnotatedGenericBeanDefinition configBeanDef = new AnnotatedGenericBeanDefinition(metadata);

		// 设置动态代理模型，关于web的
		ScopeMetadata scopeMetadata = scopeMetadataResolver.resolveScopeMetadata(configBeanDef);
		configBeanDef.setScope(scopeMetadata.getScopeName());
		// 通过 BeanName 生成器创建 BeanName
		String configBeanName = this.importBeanNameGenerator.generateBeanName(configBeanDef, this.registry);
		// 解析配置类的一些其它注解
		AnnotationConfigUtils.processCommonDefinitionAnnotations(configBeanDef, metadata);

		// 构建Holder封装对象
		BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(configBeanDef, configBeanName);
		// 关于动态代理模型
		definitionHolder = AnnotationConfigUtils.applyScopedProxyMode(scopeMetadata, definitionHolder, this.registry);
		// 将配置类的BeanDefinition注册进容器中
		this.registry.registerBeanDefinition(definitionHolder.getBeanName(), definitionHolder.getBeanDefinition());
		// 给自己设置BeanName
		configClass.setBeanName(configBeanName);

		if (logger.isDebugEnabled()) {
			logger.debug("Registered bean definition for imported class '" + configBeanName + "'");
		}
	}

	/**
	 * 处理配置类中 @Bean 注解的方法 @param beanMethod
	 *
	 * Read the given {@link BeanMethod}, registering bean definitions
	 * with the BeanDefinitionRegistry based on its contents.
	 */
	private void loadBeanDefinitionsForBeanMethod(BeanMethod beanMethod) {
		// 获得方法所在类的类信息
		ConfigurationClass configClass = beanMethod.getConfigurationClass();
		// 获得方法的元信息
		MethodMetadata metadata = beanMethod.getMetadata();
		// 获得方法名
		String methodName = metadata.getMethodName();

		// Do we need to mark the bean as skipped by its condition?
		// 此方法是否需要跳过解析
		if (this.conditionEvaluator.shouldSkip(metadata, ConfigurationPhase.REGISTER_BEAN)) {
			configClass.skippedBeanMethods.add(methodName);
			return;
		}
		if (configClass.skippedBeanMethods.contains(methodName)) {
			return;
		}

		// 获得@Bean注解中的属性
		AnnotationAttributes bean = AnnotationConfigUtils.attributesFor(metadata, Bean.class);
		Assert.state(bean != null, "No @Bean annotation attributes");

		// Consider name and any aliases
		// 获得 @Bean 注解中指定方法返回对象的 BeanName
		// 如果没有设置则默认使用方法名
		// 如果设置了则使用第一个名称，其它名称作为别名，因为 @Bean 注解中name属性为数组
		List<String> names = new ArrayList<>(Arrays.asList(bean.getStringArray("name")));
		String beanName = (!names.isEmpty() ? names.remove(0) : methodName);

		// Register aliases even when overridden
		for (String alias : names) {
			this.registry.registerAlias(beanName, alias);
		}

		// Has this effectively been overridden before (e.g. via XML)?
		// 判断容器中与beanName相同的BeanDefinition是否可以被覆盖
		if (isOverriddenByExistingDefinition(beanMethod, beanName)) {
			// 如果 @Bean 所在方法配置的 beanName 与配置类的 BeanName 相同，则抛异常
			if (beanName.equals(beanMethod.getConfigurationClass().getBeanName())) {
				throw new BeanDefinitionStoreException(beanMethod.getConfigurationClass().getResource().getDescription(),
						beanName, "Bean name derived from @Bean method '" + beanMethod.getMetadata().getMethodName() +
						"' clashes with bean name for containing configuration class; please make those names unique!");
			}
			return;
		}

		// 通过 @Bean 所在方法元信息和类信息构建 BeanDefinition
		ConfigurationClassBeanDefinition beanDef = new ConfigurationClassBeanDefinition(configClass, metadata);
		// 设置 BeanDefinition 的提取源，即所代表的对象从哪里提取，取决于配置机制
		// 默认使用 PassThroughSourceExtractor 这个类，机制是按原样返回，即从方法中提取
		beanDef.setSource(this.sourceExtractor.extractSource(metadata, configClass.getResource()));

		// 如果方法是static静态类型，将其置为静态方法调用
		// 实际就是向 BeanDefinition 中设置工厂方法名称，即产生对象需要调用的方法
		if (metadata.isStatic()) {
			// static @Bean method
			beanDef.setBeanClassName(configClass.getMetadata().getClassName());
			beanDef.setFactoryMethodName(methodName);
		}
		// 如果不是静态类型，将其置为实例调用
		else {
			// instance @Bean method
			beanDef.setFactoryBeanName(configClass.getBeanName());
			beanDef.setUniqueFactoryMethodName(methodName);
		}
		// 设置由方法提取的实例为属性自动注入的方式，默认为构造方法
		beanDef.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
		// 是否跳过属性检查
		beanDef.setAttribute(RequiredAnnotationBeanPostProcessor.SKIP_REQUIRED_CHECK_ATTRIBUTE, Boolean.TRUE);

		// 提取方法上的其它注解
		AnnotationConfigUtils.processCommonDefinitionAnnotations(beanDef, metadata);

		// 设置由方法提取的实例是否会根据类型或者名称为属性自动注入，默认不会，除非显示设置
		Autowire autowire = bean.getEnum("autowire");
		// 指定由方法提取的实例的初始化方法名
		if (autowire.isAutowire()) {
			beanDef.setAutowireMode(autowire.value());
		}

		// 指定由方法提取的实例的销毁方法名
		String initMethodName = bean.getString("initMethod");
		if (StringUtils.hasText(initMethodName)) {
			beanDef.setInitMethodName(initMethodName);
		}

		// 指定由方法提取的实例的销毁方法名
		String destroyMethodName = bean.getString("destroyMethod");
		beanDef.setDestroyMethodName(destroyMethodName);

		// Consider scoping
		// 设置动态代理模型的初始值
		ScopedProxyMode proxyMode = ScopedProxyMode.NO;
		// 获得方法上的 @Scope 注解
		AnnotationAttributes attributes = AnnotationConfigUtils.attributesFor(metadata, Scope.class);
		// 存在 @Scope 注解时进行相关的设置
		if (attributes != null) {
			beanDef.setScope(attributes.getString("value"));
			// 设置动态代理模型，如果是使用默认的话则置为初始化值
			proxyMode = attributes.getEnum("proxyMode");
			if (proxyMode == ScopedProxyMode.DEFAULT) {
				proxyMode = ScopedProxyMode.NO;
			}
		}

		// Replace the original bean definition with the target one, if necessary
		BeanDefinition beanDefToRegister = beanDef;
		// 如果需要动态代理的话，则将原始的 BeanDefinition 替换为动态代理的 BeanDefinition
		if (proxyMode != ScopedProxyMode.NO) {
			BeanDefinitionHolder proxyDef = ScopedProxyCreator.createScopedProxy(
					new BeanDefinitionHolder(beanDef, beanName), this.registry,
					proxyMode == ScopedProxyMode.TARGET_CLASS);
			beanDefToRegister = new ConfigurationClassBeanDefinition(
					(RootBeanDefinition) proxyDef.getBeanDefinition(), configClass, metadata);
		}

		if (logger.isDebugEnabled()) {
			logger.debug(String.format("Registering bean definition for @Bean method %s.%s()",
					configClass.getMetadata().getClassName(), beanName));
		}
		// 将其注册进容器中
		this.registry.registerBeanDefinition(beanName, beanDefToRegister);
	}

	/**
	 * 如果容器中存在与beanName相同的 BeanDefinition 是否可以被覆盖
	 * 返回false则是没有与beanName相同的 BeanDefinition 或者可以被覆盖
	 * 返回true则不能覆盖
	 */
	protected boolean isOverriddenByExistingDefinition(BeanMethod beanMethod, String beanName) {
		// 根据beanName判断容器中是否已经存在相应的 BeanDefinition，不存在则返回false可以注册到容器中
		if (!this.registry.containsBeanDefinition(beanName)) {
			return false;
		}
		// 获取容器中BeanName对应的 BeanDefinition
		BeanDefinition existingBeanDef = this.registry.getBeanDefinition(beanName);

		// Is the existing bean definition one that was created from a configuration class?
		// -> allow the current bean method to override, since both are at second-pass level.
		// However, if the bean method is an overloaded case on the same configuration class,
		// preserve the existing bean definition.
		/*
		 * 如果容器中的 BeanDefinition 是一个 ConfigurationClassBeanDefinition
		 * 		ConfigurationClassBeanDefinition 是由@Bean注解方法返回对象封装的
		 * 如果配置类的类名和@Bean方法所在类的类名相同，则返回true，这时发生了重载，不解析@Bean所在的方法
		 * 如果类名不相同，则解析@Bean所在的方法，覆盖容器中存在的
		 */
		if (existingBeanDef instanceof ConfigurationClassBeanDefinition) {
			ConfigurationClassBeanDefinition ccbd = (ConfigurationClassBeanDefinition) existingBeanDef;
			return ccbd.getMetadata().getClassName().equals(
					beanMethod.getConfigurationClass().getMetadata().getClassName());
		}

		// A bean definition resulting from a component scan can be silently overridden
		// by an @Bean method, as of 4.2...
		// 如果容器中存在的 BeanDefinition 是通过扫描注册的，则@Bean方法的 BeanDefinition 会覆盖容器中存在的
		if (existingBeanDef instanceof ScannedGenericBeanDefinition) {
			return false;
		}

		// Has the existing bean definition bean marked as a framework-generated bean?
		// -> allow the current bean method to override it, since it is application-level
		// 如果容器中存在的 BeanDefinition 是应用程序级别的，则@Bean方法的 BeanDefinition 可以覆盖容器中的
		if (existingBeanDef.getRole() > BeanDefinition.ROLE_APPLICATION) {
			return false;
		}

		// At this point, it's a top-level override (probably XML), just having been parsed
		// before configuration class processing kicks in...
		/*
		 * 通过以上判断，仍然不能确定容器中的BeanDefinition是否能被覆盖
		 * 如果当前注册器实现了 DefaultListableBeanFactory (肯定实现了)并且 DefaultListableBeanFactory 不允许覆盖
		 * BeanDefinition，此时就会抛异常，因为不允许覆盖的情况下而应用程序中出现了两个相同的 BeanDefinition
		 * 如果允许覆盖，则返回true，注意此时也是不会解析@Bean所在方法的，这里的允许覆盖是允许其它来源的 BeanDefinition 可以覆盖，比如XML
		 */
		if (this.registry instanceof DefaultListableBeanFactory &&
				!((DefaultListableBeanFactory) this.registry).isAllowBeanDefinitionOverriding()) {
			throw new BeanDefinitionStoreException(beanMethod.getConfigurationClass().getResource().getDescription(),
					beanName, "@Bean definition illegally overridden by existing bean definition: " + existingBeanDef);
		}
		if (logger.isInfoEnabled()) {
			logger.info(String.format("Skipping bean definition for %s: a definition for bean '%s' " +
					"already exists. This top-level bean definition is considered as an override.",
					beanMethod, beanName));
		}
		return true;
	}

	private void loadBeanDefinitionsFromImportedResources(
			Map<String, Class<? extends BeanDefinitionReader>> importedResources) {

		Map<Class<?>, BeanDefinitionReader> readerInstanceCache = new HashMap<>();

		importedResources.forEach((resource, readerClass) -> {
			// Default reader selection necessary?
			if (BeanDefinitionReader.class == readerClass) {
				if (StringUtils.endsWithIgnoreCase(resource, ".groovy")) {
					// When clearly asking for Groovy, that's what they'll get...
					readerClass = GroovyBeanDefinitionReader.class;
				}
				else {
					// Primarily ".xml" files but for any other extension as well
					readerClass = XmlBeanDefinitionReader.class;
				}
			}

			BeanDefinitionReader reader = readerInstanceCache.get(readerClass);
			if (reader == null) {
				try {
					// Instantiate the specified BeanDefinitionReader
					reader = readerClass.getConstructor(BeanDefinitionRegistry.class).newInstance(this.registry);
					// Delegate the current ResourceLoader to it if possible
					if (reader instanceof AbstractBeanDefinitionReader) {
						AbstractBeanDefinitionReader abdr = ((AbstractBeanDefinitionReader) reader);
						abdr.setResourceLoader(this.resourceLoader);
						abdr.setEnvironment(this.environment);
					}
					readerInstanceCache.put(readerClass, reader);
				}
				catch (Throwable ex) {
					throw new IllegalStateException(
							"Could not instantiate BeanDefinitionReader class [" + readerClass.getName() + "]");
				}
			}

			// TODO SPR-6310: qualify relative path locations as done in AbstractContextLoader.modifyLocations
			reader.loadBeanDefinitions(resource);
		});
	}

	/**
	 * 执行@Import注解中实现了 ImportBeanDefinitionRegistrar 接口的类，其实就是执行实现了接口的方法
	 * 这里使用了lambda表达式遍历
	 */
	private void loadBeanDefinitionsFromRegistrars(Map<ImportBeanDefinitionRegistrar, AnnotationMetadata> registrars) {
		registrars.forEach((registrar, metadata) ->
				registrar.registerBeanDefinitions(metadata, this.registry));
	}


	/**
	 * {@link RootBeanDefinition} marker subclass used to signify that a bean definition
	 * was created from a configuration class as opposed to any other configuration source.
	 * Used in bean overriding cases where it's necessary to determine whether the bean
	 * definition was created externally.
	 */
	@SuppressWarnings("serial")
	private static class ConfigurationClassBeanDefinition extends RootBeanDefinition implements AnnotatedBeanDefinition {

		private final AnnotationMetadata annotationMetadata;

		private final MethodMetadata factoryMethodMetadata;

		public ConfigurationClassBeanDefinition(ConfigurationClass configClass, MethodMetadata beanMethodMetadata) {
			this.annotationMetadata = configClass.getMetadata();
			this.factoryMethodMetadata = beanMethodMetadata;
			setResource(configClass.getResource());
			setLenientConstructorResolution(false);
		}

		public ConfigurationClassBeanDefinition(
				RootBeanDefinition original, ConfigurationClass configClass, MethodMetadata beanMethodMetadata) {
			super(original);
			this.annotationMetadata = configClass.getMetadata();
			this.factoryMethodMetadata = beanMethodMetadata;
		}

		private ConfigurationClassBeanDefinition(ConfigurationClassBeanDefinition original) {
			super(original);
			this.annotationMetadata = original.annotationMetadata;
			this.factoryMethodMetadata = original.factoryMethodMetadata;
		}

		@Override
		public AnnotationMetadata getMetadata() {
			return this.annotationMetadata;
		}

		@Override
		public MethodMetadata getFactoryMethodMetadata() {
			return this.factoryMethodMetadata;
		}

		@Override
		public boolean isFactoryMethod(Method candidate) {
			return (super.isFactoryMethod(candidate) && BeanAnnotationHelper.isBeanAnnotated(candidate));
		}

		@Override
		public ConfigurationClassBeanDefinition cloneBeanDefinition() {
			return new ConfigurationClassBeanDefinition(this);
		}
	}


	/**
	 * Evaluate {@code @Conditional} annotations, tracking results and taking into
	 * account 'imported by'.
	 */
	private class TrackedConditionEvaluator {

		private final Map<ConfigurationClass, Boolean> skipped = new HashMap<>();

		public boolean shouldSkip(ConfigurationClass configClass) {
			Boolean skip = this.skipped.get(configClass);
			if (skip == null) {
				if (configClass.isImported()) {
					boolean allSkipped = true;
					for (ConfigurationClass importedBy : configClass.getImportedBy()) {
						if (!shouldSkip(importedBy)) {
							allSkipped = false;
							break;
						}
					}
					if (allSkipped) {
						// The config classes that imported this one were all skipped, therefore we are skipped...
						skip = true;
					}
				}
				if (skip == null) {
					skip = conditionEvaluator.shouldSkip(configClass.getMetadata(), ConfigurationPhase.REGISTER_BEAN);
				}
				this.skipped.put(configClass, skip);
			}
			return skip;
		}
	}

}
