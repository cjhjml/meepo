package org.feisoft.jta.supports.jdbc;

import com.alibaba.fastjson.JSON;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import org.apache.commons.lang3.StringUtils;
import org.feisoft.common.utils.DbPool.DbPoolUtil;
import org.feisoft.common.utils.SqlpraserUtils;
import org.feisoft.jta.TransactionImpl;
import org.feisoft.jta.image.BackInfo;
import org.feisoft.jta.image.Resolvers.BaseResolvers;
import org.feisoft.jta.image.Resolvers.InsertImageResolvers;
import org.feisoft.jta.lock.MutexLock;
import org.feisoft.jta.lock.ShareLock;
import org.feisoft.jta.lock.TxcLock;
import org.feisoft.jta.supports.spring.SpringBeanUtil;
import org.feisoft.transaction.TransactionBeanFactory;
import org.feisoft.transaction.archive.XAResourceArchive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.transaction.Status;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DynamicPreparedStatementProxyHandler implements InvocationHandler {

    Logger logger = LoggerFactory.getLogger(DynamicPreparedStatementProxyHandler.class);

    List<String> proxyUpdateMethods = Arrays
            .asList("executeUpdate", "execute", "executeBatch", "executeLargeBatch", "executeLargeUpdate");

    List<String> insideTableNames = Arrays.asList("txc_lock", "txc_undo_log");

    private Object realObject;

    private String sql;

    private XAConnection xaConn;

    public Xid currentXid;

    private String GloableXid;

    private String branchXid;

    private List<Object> params = new ArrayList<>();

    private int timeOut = 20 * 1000;

    public DynamicPreparedStatementProxyHandler(Object realObject, String sql, XAConnection conn) {
        this.realObject = realObject;
        this.sql = sql;
        this.xaConn = conn;

    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

        TransactionBeanFactory jtaBeanFactory = (TransactionBeanFactory) SpringBeanUtil.getBean("jtaBeanFactory");
        TransactionImpl transaction = (TransactionImpl) jtaBeanFactory.getTransactionManager().getTransaction();

        if (transaction == null) {
            return method.invoke(realObject, args);
        }

        if (method.getName().startsWith("set") && args != null && args.length == 2) {
            params.add(args[1]);
        }

        if (innerMethod(args))
            return method.invoke(realObject, args);

        if (transaction.getStatus() == Status.STATUS_ROLLEDBACK) {
            throw new XAException("Transaction STATUS_MARKED_ROLLBACK");
        }

        if (isQueryMethod(method) || isUpdateMethod(method.getName())) {
            logger.info("method={},sql={}", method, sql);
            if (currentXid == null) {
                List<XAResourceArchive> xaResourceArchives = transaction.getNativeParticipantList();
                if (xaResourceArchives.size() > 0) {
                    currentXid = xaResourceArchives.get(0).getXid();
                }
            }
            if (currentXid != null) {
                GloableXid = partGloableXid(currentXid);
                branchXid = partBranchXid(currentXid);
            }

        }

        if (isUpdateMethod(method.getName())) {

            if (currentXid == null) {
                logger.error("method.getName()={},args={}-----没有xid-----,thread={}", method.getName(), args,
                        Thread.currentThread().getName());

            }
            return invokUpdate(method, args);
        }

        if (isQueryMethod(method)) {

            try {
                if (args == null || StringUtils.isEmpty(args[0].toString())) {
                    //select x
                    return method.invoke(realObject, args);
                }
                sql = args[0].toString();
                if (StringUtils.deleteWhitespace(sql.toLowerCase()).endsWith("lockinsharemode")) {
                    return LockSharedMode(method, args);
                } else {
                    net.sf.jsqlparser.statement.Statement statement;
                    try {
                        statement = CCJSqlParserUtil.parse(sql);
                    } catch (JSQLParserException e) {
                        logger.error("jsqlparser.praseFailed,sql=" + sql);
                        return method.invoke(realObject, args);
                    }
                    Select select = (Select) statement;
                    SelectBody selectBody = select.getSelectBody();
                    if (selectBody instanceof PlainSelect) {
                        PlainSelect ps = (PlainSelect) selectBody;
                        if (ps.isForUpdate()) {
                            // update
                            return lockForUpdate(method, args);
                        }
                    }

                }
            } finally {
                xaCommit(method, args);
            }
        }
        return method.invoke(realObject, args);
    }

    private boolean innerMethod(Object[] args) throws JSQLParserException {
        if (args != null) {
            Object param0 = args[0];
            if (param0!=null && param0 instanceof String) {
                String strParam0 = param0.toString();
                if (SqlpraserUtils.assertExeSql(strParam0)) {
                    String tableName = SqlpraserUtils.name_exesql_table(strParam0);
                    //内部表使用sql
                    if (isInsideSql(tableName)) {
                        return true;
                    }
                }
            }
        } else if (StringUtils.isNotBlank(sql)&&SqlpraserUtils.assertExeSql(sql)) {
            String tableName = SqlpraserUtils.name_exesql_table(sql);
            //内部表使用sql
            if (isInsideSql(tableName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInsideSql(String tableName) {
        return insideTableNames.contains(tableName);
    }

    private boolean isQueryMethod(Method method) {
        return "executeQuery".equals(method.getName());
    }

    private boolean isUpdateMethod(String methodName) {
        return proxyUpdateMethods.contains(methodName);
    }

    private Object lockForUpdate(Method method, Object[] args)
            throws SQLException, XAException, JSQLParserException, IllegalAccessException, InvocationTargetException {
        BackInfo backInfo = new BackInfo();
        try {

            BaseResolvers resolver = BaseResolvers.newInstance(sql, backInfo);
            backInfo.setBeforeImage(resolver.genBeforeImage());
            getXlock(resolver, GloableXid, branchXid);
        } finally {
            xaCommit(method, args);
        }

        return method.invoke(realObject, args);
    }

    private Object LockSharedMode(Method method, Object[] args)
            throws SQLException, XAException, JSQLParserException, IllegalAccessException, InvocationTargetException {
        BackInfo backInfo = new BackInfo();

        Object obj;
        try {
            BaseResolvers resolver = BaseResolvers
                    .newInstance(sql.substring(0, sql.toLowerCase().indexOf("lock")), backInfo);
            backInfo.setBeforeImage(resolver.genBeforeImage());

            getSlock(resolver, GloableXid, branchXid);

            obj = method.invoke(realObject, args);
        } finally {
            xaCommit(method, args);
        }

        return obj;
    }

    private Object invokUpdate(Method method, Object[] args)
            throws SQLException, XAException, JSQLParserException, IllegalAccessException, InvocationTargetException {
        BackInfo backInfo = new BackInfo();
        Object obj = null;
        Object pkVal = null;

        if (realObject instanceof PreparedStatement) {
            sql = printRealSql(sql, params);
        } else if (realObject instanceof Statement) {

            sql = args[0].toString();
        }
        //事务数据源从对应数据库获取前置对象

        BaseResolvers resolver;
        try {
            resolver = BaseResolvers.newInstance(sql, backInfo);
            backInfo.setBeforeImage(resolver.genBeforeImage());
            logger.info("before getXlock,GloableXid=" + GloableXid + ",branchXid=" + branchXid);
            getXlock(resolver, GloableXid, branchXid);

            if (SqlpraserUtils.assertInsert(sql)) {
                ResultSet generatedKeys = null;
                if (realObject instanceof PreparedStatement) {
                    obj = method.invoke(realObject, args);
                    PreparedStatement preparedStatement = (PreparedStatement) realObject;
                    generatedKeys = preparedStatement.getGeneratedKeys();
                } else if (realObject instanceof Statement) {
                    Statement realSt = (Statement) realObject;
                    obj = realSt.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
                    generatedKeys = realSt.getGeneratedKeys();
                }
                if (generatedKeys != null) {
                    while (generatedKeys.next()) {
                        pkVal = generatedKeys.getObject(1);
                    }
                    if (!generatedKeys.isClosed()) {
                        generatedKeys.close();
                    }
                }

                if (pkVal == null) {
                    String pkKey = resolver.getMetaPrimaryKey(SqlpraserUtils.name_insert_table(sql));
                    List<String> colums = SqlpraserUtils.name_insert_column(sql);
                    List<String> values = SqlpraserUtils.name_insert_values(sql);
                    if (colums.contains(pkKey)) {
                        pkVal = values.get(colums.indexOf(pkKey));
                    }
                }
            } else {
                obj = method.invoke(realObject, args);
            }
        } finally {
            xaCommit(method, args);

        }

        //插入时需要获取主键的value
        if (SqlpraserUtils.assertInsert(sql)) {
            InsertImageResolvers inResolver = (InsertImageResolvers) resolver;
            inResolver.setPkVal(pkVal);
            backInfo.setAfterImage(inResolver.genAfterImage());
        } else {
            backInfo.setAfterImage(resolver.genAfterImage());
        }
        String backSqlJson = JSON.toJSONString(backInfo);
        String logSql = "INSERT INTO txc_undo_log (gmt_create,gmt_modified,xid,branch_id,rollback_info,status,server) VALUES(now(),now(),?,?,?,?,?)";
        DbPoolUtil.executeUpdate(logSql, GloableXid, branchXid, backSqlJson, 0, getHost());

        //事务数据源从对应数据库获取后置对象
        return obj;
    }

    private void xaCommit(Method method, Object[] args) {
        XAResource resource;
        try {
            //本地直接提交
            resource = xaConn.getXAResource();
            try {
                resource.end(currentXid, XAResource.TMSUCCESS);
            } catch (XAException e) {
            }
            try {
                resource.prepare(currentXid);
            } catch (XAException e) {
            }
            resource.commit(currentXid, false);
        } catch (Exception ex) {
            logger.info("Local multi sqls exe!method={},args={}", method.getName(), args);
        }
    }

    private String getHost() throws SQLException {
        Connection conn = DbPoolUtil.getConnection();
        DatabaseMetaData md = conn.getMetaData();
        String url = md.getURL();
        String host = "";
        Pattern p = Pattern.compile("(?<=//|)((\\w)+\\.)+\\w+");
        Matcher matcher = p.matcher(url);
        if (matcher.find()) {
            host = matcher.group();
        }
        DbPoolUtil.close(conn, null, null);
        return host;
    }

    private void getXlock(BaseResolvers resolver, String gloableXid, String branchXid)
            throws XAException, JSQLParserException, SQLException {

        long atime = System.currentTimeMillis();

        long btime;
        do {
            btime = System.currentTimeMillis();
            List<TxcLock> lockList = this.getMutexLock(gloableXid, branchXid, resolver);
            if (this.lockCurrent(lockList)) {
                return;
            }
        } while (btime - atime <= (long) this.timeOut);

        throw new XAException("Proxy.getLockTimeout");

    }

    private void getSlock(BaseResolvers resolver, String gloableXid, String branchXid)
            throws XAException, JSQLParserException, SQLException {

        long atime = System.currentTimeMillis();

        long btime;
        do {
            btime = System.currentTimeMillis();
            List<TxcLock> lockList = getShareLock(gloableXid, branchXid, resolver, sql);
            if (lockCurrent(lockList))
                return;

        } while (btime - atime <= (long) this.timeOut);
        throw new XAException("Proxy.getLockTimeout");
    }

    private boolean lockCurrent(List<TxcLock> lockList) {
        if (lockList.size() > 0) {
            for (TxcLock lock : lockList) {
                try {
                    if (!lock.isLock()) {
                        lock.lock();
                    }
                } catch (Exception e) {
                    logger.info("getXlock -- Data locked by other,retry");
                    return false;
                }
                lock.setLock(true);
            }
        }
        return true;
    }

    private List<TxcLock> getMutexLock(String gloableXid, String branchXid, BaseResolvers resolver)
            throws XAException, JSQLParserException, SQLException {

        String beforeLockSql = resolver.getLockedSet();
        String primaryKey = resolver.getMetaPrimaryKey(resolver.getTable());

        List<String> allList = DbPoolUtil.executeQuery(beforeLockSql, rs -> rs.getObject(primaryKey).toString(), null);
        List<TxcLock> lockList = new ArrayList<>();

        if (allList.size() == 0) {
            return lockList;
        }
        String lockSql = "select key_value from txc_lock where xid='" + gloableXid + "'  and branch_id ='" + branchXid
                + "' and table_name = '" + resolver.getTable() + "' and key_value in(" + resolver.transList(allList)
                + ")";

        List<String> lockedList = DbPoolUtil.executeQuery(lockSql, rs -> rs.getObject("key_value").toString(), null);

        allList.removeAll(lockedList);
        for (String unlockRecord : allList) {

            MutexLock lock = new MutexLock();
            lock.setLock(Boolean.FALSE);
            lock.setXid(gloableXid);
            lock.setBranchId(branchXid);
            lock.setTableName(resolver.getTable());
            lock.setXlock("1");
            lock.setSlock(0);
            lock.setKeyValue(unlockRecord);
            lock.setCreateTime(System.currentTimeMillis());
            lockList.add(lock);
        }

        return lockList;
    }

    private List<TxcLock> getShareLock(String gloableXid, String branchXid, BaseResolvers resolver, String sql)
            throws XAException, JSQLParserException, SQLException {

        String beforeLockSql = resolver.getLockedSet();

        String primaryKey = resolver.getMetaPrimaryKey(resolver.getTable());
        List<String> allList = DbPoolUtil.executeQuery(beforeLockSql, rs -> rs.getObject(primaryKey).toString(), null);

        List<TxcLock> lockList = new ArrayList<>();

        String lockSql =
                "select key_value,count(*) as count from txc_lock where xid='" + gloableXid + "'  and branch_id ='"
                        + branchXid + "' and table_name = '" + resolver.getTable() + "' and key_value in(" + resolver
                        .transList(allList) + ") group by key_value";
        List<String> lockedList = DbPoolUtil.executeQuery(lockSql, rs -> {
            String tmp = null;
            if (rs.getInt("count") > 1)
                tmp = rs.getString("key_value");
            return tmp;
        }, null);

        allList.removeAll(lockedList);
        for (String r3str : allList) {

            ShareLock lock = new ShareLock();
            lock.setLock(Boolean.FALSE);
            lock.setXid(gloableXid);
            lock.setBranchId(branchXid);
            lock.setTableName(resolver.getTable());
            lock.setXlock("1");
            lock.setSlock(1);
            lock.setKeyValue(r3str);
            lock.setCreateTime(System.currentTimeMillis());
            lockList.add(lock);
        }

        return lockList;
    }

    /**
     * @param sql    SQL 语句，可以带有 ? 的占位符
     * @param params 插入到 SQL 中的参数，可单个可多个可不填
     * @return 实际 sql 语句
     */
    private String printRealSql(String sql, List<Object> params) {
        if (params == null || params.size() == 0) {
            return sql;
        }

        if (!match(sql, params)) {
            logger.error("SQL 语句中的占位符与参数个数不匹配。SQL：" + sql);
            return null;
        }

        int cols = params.size();
        Object[] values = new Object[cols];
        params.toArray(values);
        for (int i = 0; i < cols; i++) {
            Object value = values[i];
            if (value instanceof Date || value instanceof Timestamp || value instanceof String
                    || value instanceof Blob) {
                values[i] = "'" + value + "'";
            } else if (value instanceof Boolean) {
                values[i] = (Boolean) value ? 1 : 0;
            }
        }

        String statement = String.format(sql.replaceAll("\\?", "%s"), values);
        return statement;
    }

    /**
     * ? 和参数的实际个数是否匹配
     *
     * @param sql    SQL 语句，可以带有 ? 的占位符
     * @param params 插入到 SQL 中的参数，可单个可多个可不填
     * @return true 表示为 ? 和参数的实际个数匹配
     */
    private boolean match(String sql, List<Object> params) {
        if (params == null || params.size() == 0)
            return true; // 没有参数，完整输出

        Matcher m = Pattern.compile("(\\?)").matcher(sql);
        int count = 0;
        while (m.find()) {
            count++;
        }

        return count == params.size();
    }

    private String partGloableXid(Xid xid) {

        byte[] gtrid = xid.getGlobalTransactionId();

        StringBuilder builder = new StringBuilder();

        if (gtrid != null) {
            appendAsHex(builder, gtrid);
        }

        return builder.toString();
    }

    private String partBranchXid(Xid xid) {

        byte[] btrid = xid.getBranchQualifier();

        StringBuilder builder = new StringBuilder();

        if (btrid != null) {
            appendAsHex(builder, btrid);
        }

        return builder.toString();
    }

    private static final char[] HEX_DIGITS = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd',
            'e', 'f' };

    private static void appendAsHex(StringBuilder builder, byte[] bytes) {
        builder.append("0x");
        for (byte b : bytes) {
            builder.append(HEX_DIGITS[(b >>> 4) & 0xF]).append(HEX_DIGITS[b & 0xF]);
        }
    }

}
