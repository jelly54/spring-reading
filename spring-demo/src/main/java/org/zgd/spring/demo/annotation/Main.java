package org.zgd.spring.demo.annotation;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * @author zhangguodong
 * @since 2022/1/13 18:54
 */
public class Main {
	public static void main(String[] args) {
		// 这个对象创建完毕后，spring环境的准备工作完成
		AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext();
		// 向spring注册一个类
		applicationContext.register(TestConfig.class);
	}
}
