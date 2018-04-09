package com.ibeifeng.sparkproject;

/**
 * Created by wanglei on 2018/4/9.
 * 单例设计模式
 * <p>
 * 单例设计模式是什么意思？
 * 我们自己定义的类，其实默认情况下，都是可以让外界的代码随意创建任意多个实例的。
 * 但是有些时候，我们不希望外界来随意创建实例，而只是希望一个类，在整个程序运行期间，只有一个实例
 * 任何外界代码，都不能随意创建实例
 * <p>
 * 那么，要实现单例模式，有几个要点：
 * 1、如果不想让外界可以随意创建实例，那么类的构造方法就必须用private修饰，必须是私有的
 * 2、既然类的构造方法被私有化了，外界代码想要获取类的实例，不能够随意去创建，那么就只能通过调用类的静态方法，去获取类的实例
 * 3、所以类必须有一个静态方法，getInstance(),来提供获取唯一实例的功能。
 * getInstance()方法，必须保证类的实例创建，且仅创建一次，返回一个唯一的实例
 * <p>
 * 单例模式的应用场景有哪几个呢？
 * 1、配置管理组件，可以在读取大量的配置信息之后，用单例模式的方式，就将配置信息仅仅保存在一个实例的实例变量中，这样可以避免对于静态不变的配置信息，反复多次的读取
 * 2、JDBC辅助组件，全局就只有一个实例，实例中持有了一个内部的简单数据源
 * 使用了单例模式之后，就保证只有一个实例，那么数据源也只有一个，不会重复创建多次数据源（数据库连接池）
 */
public class Singleton {

    //首先，必须有一个私有的静态变量，来引用自己即将被创建出来的单例
    private static Singleton instance = null;

    //其次，必须对自己的构造方法使用private进行私有化
    //这样，才能保证，外界的代码不能随意的创建类的实例
    private Singleton() {

    }

    //最后，需要一个共有的，静态方法
    //这个方法，负责创建唯一的实例，并且返回这个唯一的实例
    //就是说，可能会有多个线程同时过来获取单例，那么可能会导致创建多次单例
    //所以，这个方法，通常需要进行多线程并发访问安全的控制
    public static Singleton getInstance() {
        if (instance == null) {
            synchronized (Singleton.class) {
                if (instance == null) {
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }


}
