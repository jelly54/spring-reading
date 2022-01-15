/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.context.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.lang.Nullable;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	/**
	 * 执行容器中所有实现 BeanFactoryPostProcessors 接口和其子接口 BeanDefinitionRegistryPostProcessor 类实现的接口方法
	 * 首先需要搞明白这两个接口关系
	 * 父接口： BeanFactoryPostProcessors  方法：postProcessBeanFactory
	 *                   ↑继承
	 * 子接口： BeanDefinitionRegistryPostProcessor  方法：postProcessBeanDefinitionRegistry
	 *                   ↑实现
	 * 集合实例：      Bean
	 * Spring对这两个接口分别做处理，具体请看代码
	 *
	 * 代码处理顺序：
	 *                                                               BeanDefinitionRegistryPostProcessor 集合
	 * 执行手动注册的 BeanDefinitionRegistryPostProcessor 接口实现Bean -----------------→\
	 * 执行容器中 BeanDefinitionRegistryPostProcessor 接口实现Bean----------------------→\
	 *       (执行顺序为 实现 PriorityOrdered 接口 -> 实现 Ordered 接口 -> 没有排序接口实现)\
	 *                      ↓--------------------------------------------------------←\
	 * 执行以上Bean的 BeanFactoryPostProcessors 接口方法
	 * 执行手动注册的 BeanFactoryPostProcessors 接口实现Bean
	 * 执行容器中 BeanFactoryPostProcessors 接口实现Bean
	 *       (执行顺序为 实现 PriorityOrdered 接口 -> 实现 Ordered 接口 -> 没有排序接口实现)
	 *
	 * @see BeanDefinitionRegistryPostProcessor
	 * @see BeanFactoryPostProcessor
	 */
	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		// Invoke BeanDefinitionRegistryPostProcessors first, if any.
		// 存放执行过接口方法的BeanName
		Set<String> processedBeans = new HashSet<>();

		/*
		 * 如果传递过来的 beanFactory 是 BeanDefinition 注册器的子类，这是需要处理 BeanDefinitionRegistryPostProcessor 情况
		 * 否则直接调用 invokeBeanFactoryPostProcessors 方法
		 */
		if (beanFactory instanceof BeanDefinitionRegistry) {
			// 将 BeanFactory 转换为 BeanDefinition 注册器类型
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			// 集合中存放 BeanFactoryPostProcessor 类型的Bean
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
			// 集合中存放 BeanDefinitionRegistryPostProcessor 类型的Bean
			// 作用是在最后执行 BeanFactoryPostProcessor 的接口方法
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();

			/**
			 * 遍历手动注册进来的 BeanFactoryPostProcessor 实例，判断类型
			 * BeanDefinitionRegistryPostProcessor 类型时就直接处理接口方法实现，后将其存入集合中
			 * 否则就是 BeanFactoryPostProcessor 类型，并放入对应的集合中
			 */
			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) {
					BeanDefinitionRegistryPostProcessor registryProcessor =
							(BeanDefinitionRegistryPostProcessor) postProcessor;
					registryProcessor.postProcessBeanDefinitionRegistry(registry);
					registryProcessors.add(registryProcessor);
				}
				else {
					regularPostProcessors.add(postProcessor);
				}
			}

			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the bean factory post-processors apply to them!
			// Separate between BeanDefinitionRegistryPostProcessors that implement
			// PriorityOrdered, Ordered, and the rest.
			// 本集合存储在容器中取出的 BeanDefinitionRegistryPostProcessor 接口实例
			// 不同于上面，本集合存储执行的Bean，执行完毕后会clear，这样循环执行
			// spring 这里把 BeanDefinitionRegistryPostProcessor 接口实现 Bean 分类执行
			// 优先级是  PriorityOrdered > Ordered > 其它
			// 因为在spring中排序接口 PriorityOrdered大于Ordered，同级则调用getOrder()方法
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();

			// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.
			// 第一步，取出容器中所有实现了 BeanDefinitionRegistryPostProcessor 接口的 BeanNames
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			// 遍历这些BeanNames，如果 BeanDefinition 对应的类是否实现了 PriorityOrdered 接口则放入执行集合
			// 并将BeanName放入已执行集合中
			for (String ppName : postProcessorNames) {
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}
			// 排序
			// 添加到已执行 BeanDefinitionRegistryPostProcessor 接口Bean集合中
			// 执行 BeanDefinitionRegistryPostProcessor 的接口方法实现
			// 清空此次执行的集合
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			registryProcessors.addAll(currentRegistryProcessors);
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
			currentRegistryProcessors.clear();

			// Next, invoke the BeanDefinitionRegistryPostProcessors that implement Ordered.
		 	// 之后，同样取出容器中所有实现了 BeanDefinitionRegistryPostProcessor 接口的BeanNames
			// 如果这些 BeanDefinition 对应的类是否实现了Ordered接口并且BeanName没有在已执行BeanName集合中
			// 则放入执行集合并将BeanName放入已执行集合中
			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}
			// 排序
			// 添加到已执行 BeanDefinitionRegistryPostProcessor 接口Bean集合中
			// 执行 BeanDefinitionRegistryPostProcessor 的接口方法实现
			// 清空此次执行的集合
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			registryProcessors.addAll(currentRegistryProcessors);
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
			currentRegistryProcessors.clear();

			// Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
			// 处理剩余的 BeanDefinitionRegistryPostProcessor 接口Bean
			// 这里使用循环处理，很有可能在处理这些剩余Bean时又有新的 BeanDefinitionRegistryPostProcessor 接口Bean
			// 被注册进BeanFactory中，所以循环处理直到 BeanFactory 中没有为止
			boolean reiterate = true;
			while (reiterate) {
				reiterate = false;
				/**
				 * 取出容器中所有实现了BeanDefinitionRegistryPostProcessor接口的BeanNames
				 * 如果这些BeanDefinition对应的类的BeanName没有在已执行BeanName集合中
				 * 则放入执行集合并将BeanName放入已执行集合中
				 * 并将标识设为true，提示有可能会有新的BeanDefinitionRegistryPostProcessor接口Bean
				 * 注入到BeanFactory中处理
				 */
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
				for (String ppName : postProcessorNames) {
					if (!processedBeans.contains(ppName)) {
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
						processedBeans.add(ppName);
						reiterate = true;
					}
				}
				/**
				 * 排序
				 * 添加到已执行BeanDefinitionRegistryPostProcessor接口Bean集合中
				 * 执行BeanDefinitionRegistryPostProcessor的接口方法实现
				 * 清空此次执行的集合
				 */
				sortPostProcessors(currentRegistryProcessors, beanFactory);
				registryProcessors.addAll(currentRegistryProcessors);
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
				currentRegistryProcessors.clear();
			}

			// Now, invoke the postProcessBeanFactory callback of all processors handled so far.
			/**
			 * 以下执行BeanFactoryPostProcessors接口方法实现
			 * 首先执行BeanDefinitionRegistryPostProcessor接口的实现Bean
			 * 之后再执行只实现了BeanFactoryPostProcessors接口的实现Bean
			 * 从这里可以看出执行顺序，BeanDefinitionRegistryPostProcessor接口Bean会先于
			 * BeanFactoryPostProcessors接口Bean
			 */
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
		}

		else {
			// Invoke factory processors registered with the context instance.
			/**
			 * 如果传递过来的beanFactory不是BeanDefinition注册器的子类
			 * 所以不需要处理BeanDefinitionRegistryPostProcessor情况
			 * 直接执行BeanFactoryPostProcessors接口方法实现
			 */
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
		}

		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let the bean factory post-processors apply to them!
		/**
		 * 注意：到此为止，spring只处理完手动注册进来的BeanDefinitionRegistryPostProcessor接口Bean和
		 * 		手动注册进来的BeanFactoryPostProcessors接口Bean和
		 * 		容器中所有的BeanDefinitionRegistryPostProcessor接口Bean和
		 * 		容器中所有的BeanDefinitionRegistryPostProcessor接口Bean的BeanFactoryPostProcessors接口方法实现
		 *
		 * Spring并没有处理容器中的BeanFactoryPostProcessors接口Bean
		 * 以下是Spring处理容器中BeanFactoryPostProcessors接口Bean
		 */

		/**
		 * 获取容器中所有BeanFactoryPostProcessors接口BeanName
		 */
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

		// Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		/**
		 * 同样，Spring对BeanFactoryPostProcessor接口Bean分类处理
		 * 实现了PriorityOrdered接口的 > 实现了Ordered接口的 > 没有实现排序接口的
		 * 这里创建了三个集合分别存储上述分类后的Bean
		 */
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();

		/**
		 * 遍历所有的BeanName，如果在已执行BeanName中的话，就不需要处理了
		 * 		因为这个Bean实现了BeanDefinitionRegistryPostProcessor接口
		 * 否则判断其排序接口放入对应的集合中
		 */
		for (String ppName : postProcessorNames) {
			if (processedBeans.contains(ppName)) {
				// skip - already processed in first phase above
			}
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, invoke the BeanFactoryPostProcessors that implement PriorityOrdered.
		//  排序
		//	执行实现了 BeanFactoryPostProcessors 和PriorityOrdered接口的Bean
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);

		// Next, invoke the BeanFactoryPostProcessors that implement Ordered.
		/**
		 * 依据BeanName取出Bean放入执行集合中
		 * 排序
		 * 执行实现了BeanFactoryPostProcessors和Ordered接口的Bean
		 */
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>();
		for (String postProcessorName : orderedPostProcessorNames) {
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);

		// Finally, invoke all other BeanFactoryPostProcessors.
		/**
		 * 依据BeanName取出Bean放入执行集合中
		 * 排序
		 * 执行实现了BeanFactoryPostProcessors接口并没有实现排序接口的Bean
		 */
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>();
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);

		// Clear cached merged bean definitions since the post-processors might have
		// modified the original metadata, e.g. replacing placeholders in values...
		/**
		 * 清除缓存
		 */
		beanFactory.clearMetadataCache();
	}

	public static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {

		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs an info message when
		// a bean is created during BeanPostProcessor instantiation, i.e. when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount));

		// Separate between BeanPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
				priorityOrderedPostProcessors.add(pp);
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					internalPostProcessors.add(pp);
				}
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, register the BeanPostProcessors that implement PriorityOrdered.
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

		// Next, register the BeanPostProcessors that implement Ordered.
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>();
		for (String ppName : orderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			orderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, orderedPostProcessors);

		// Now, register all regular BeanPostProcessors.
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>();
		for (String ppName : nonOrderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			nonOrderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);

		// Finally, re-register all internal BeanPostProcessors.
		sortPostProcessors(internalPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory) {
			comparatorToUse = ((DefaultListableBeanFactory) beanFactory).getDependencyComparator();
		}
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * Invoke the given BeanDefinitionRegistryPostProcessor beans.
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors(
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry) {

		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanDefinitionRegistry(registry);
		}
	}

	/**
	 * Invoke the given BeanFactoryPostProcessor beans.
	 */
	private static void invokeBeanFactoryPostProcessors(
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanFactory(beanFactory);
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 */
	private static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanPostProcessor> postProcessors) {

		for (BeanPostProcessor postProcessor : postProcessors) {
			beanFactory.addBeanPostProcessor(postProcessor);
		}
	}


	/**
	 * BeanPostProcessor that logs an info message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory, int beanPostProcessorTargetCount) {
			this.beanFactory = beanFactory;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				if (logger.isInfoEnabled()) {
					logger.info("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying)");
				}
			}
			return bean;
		}

		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == RootBeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}

}
