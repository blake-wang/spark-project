package com.bf.sparkproject.dao.impl;

import com.bf.sparkproject.dao.ITaskDAO;

/**
 * Created by wanglei on 2018/4/9.
 * DAO工厂类
 */
public class DAOFactory {
    /**
     * 获取任务管理DAO
     * @return
     */
    public static ITaskDAO getTaskDAO() {
        return new TaskDAOImpl();
    }
}
