/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.lens.server.query;

import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;

import static org.testng.Assert.*;

import java.io.*;
import java.util.*;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.lens.api.APIResult;
import org.apache.lens.api.LensConf;
import org.apache.lens.api.LensSessionHandle;
import org.apache.lens.api.error.LensCommonErrorCode;
import org.apache.lens.api.query.*;
import org.apache.lens.api.query.QueryStatus.Status;
import org.apache.lens.api.result.*;
import org.apache.lens.driver.hive.HiveDriver;
import org.apache.lens.driver.hive.TestHiveDriver;
import org.apache.lens.server.LensJerseyTest;
import org.apache.lens.server.LensServices;
import org.apache.lens.server.LensTestUtil;
import org.apache.lens.server.api.LensConfConstants;
import org.apache.lens.server.api.driver.LensDriver;
import org.apache.lens.server.api.error.LensException;
import org.apache.lens.server.api.metrics.LensMetricsRegistry;
import org.apache.lens.server.api.metrics.MetricsService;
import org.apache.lens.server.api.query.AbstractQueryContext;
import org.apache.lens.server.api.query.QueryContext;
import org.apache.lens.server.api.session.SessionService;
import org.apache.lens.server.common.ErrorResponseExpectedData;
import org.apache.lens.server.common.TestDataUtils;
import org.apache.lens.server.common.TestResourceFile;
import org.apache.lens.server.error.LensExceptionMapper;
import org.apache.lens.server.session.HiveSessionService;
import org.apache.lens.server.session.LensSessionImpl;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.test.TestProperties;
import org.testng.Assert;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import com.codahale.metrics.MetricRegistry;

import lombok.extern.slf4j.Slf4j;

/**
 * The Class TestQueryService.
 */
@Slf4j
@Test(groups = "unit-test")
public class TestQueryService extends LensJerseyTest {

  /** The query service. */
  QueryExecutionServiceImpl queryService;

  /** The metrics svc. */
  MetricsService metricsSvc;

  /** The lens session id. */
  LensSessionHandle lensSessionId;

  public static class QueryServiceTestApp extends QueryApp {

    @Override
    public Set<Class<?>> getClasses() {
      final Set<Class<?>> classes = super.getClasses();
      classes.add(LensExceptionMapper.class);
      classes.add(LensJAXBContextResolver.class);
      return classes;
    }
  }
  /*
   * (non-Javadoc)
   *
   * @see org.glassfish.jersey.test.JerseyTest#setUp()
   */
  @BeforeTest
  public void setUp() throws Exception {
    super.setUp();
    queryService = (QueryExecutionServiceImpl) LensServices.get().getService("query");
    metricsSvc = (MetricsService) LensServices.get().getService(MetricsService.NAME);
    Map<String, String> sessionconf = new HashMap<String, String>();
    sessionconf.put("test.session.key", "svalue");
    lensSessionId = queryService.openSession("foo@localhost", "bar", sessionconf); // @localhost should be removed
    // automatically
    createTable(TEST_TABLE);
    loadData(TEST_TABLE, TestResourceFile.TEST_DATA2_FILE.getValue());
  }

  /*
   * (non-Javadoc)
   *
   * @see org.glassfish.jersey.test.JerseyTest#tearDown()
   */
  @AfterTest
  public void tearDown() throws Exception {
    dropTable(TEST_TABLE);
    queryService.closeSession(lensSessionId);
    for (LensDriver driver : queryService.getDrivers()) {
      if (driver instanceof HiveDriver) {
        assertFalse(((HiveDriver) driver).hasLensSession(lensSessionId));
      }
    }
    super.tearDown();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.glassfish.jersey.test.JerseyTest#configure()
   */
  @Override
  protected Application configure() {
    enable(TestProperties.LOG_TRAFFIC);
    enable(TestProperties.DUMP_ENTITY);
    return new QueryServiceTestApp();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.glassfish.jersey.test.JerseyTest#configureClient(org.glassfish.jersey.client.ClientConfig)
   */
  @Override
  protected void configureClient(ClientConfig config) {
    config.register(MultiPartFeature.class);
    config.register(LensJAXBContextResolver.class);
  }

  /** The test table. */
  public static final String TEST_TABLE = "TEST_TABLE";

  /**
   * Creates the table.
   *
   * @param tblName the tbl name
   * @throws InterruptedException the interrupted exception
   */
  private void createTable(String tblName) throws InterruptedException {
    LensTestUtil.createTable(tblName, target(), lensSessionId);
  }

  /**
   * Load data.
   *
   * @param tblName      the tbl name
   * @param testDataFile the test data file
   * @throws InterruptedException the interrupted exception
   */
  private void loadData(String tblName, final String testDataFile) throws InterruptedException {
    LensTestUtil.loadDataFromClasspath(tblName, testDataFile, target(), lensSessionId);
  }

  /**
   * Drop table.
   *
   * @param tblName the tbl name
   * @throws InterruptedException the interrupted exception
   */
  private void dropTable(String tblName) throws InterruptedException {
    LensTestUtil.dropTable(tblName, target(), lensSessionId);
  }

  // test get a random query, should return 400

  /**
   * Test get random query.
   */
  @Test
  public void testGetRandomQuery() {
    final WebTarget target = target().path("queryapi/queries");

    Response rs = target.path("random").queryParam("sessionid", lensSessionId).request().get();
    Assert.assertEquals(rs.getStatus(), 400);
  }

  /**
   * Test launch fail.
   *
   * @throws InterruptedException the interrupted exception
   */
  @Test
  public void testLaunchFail() throws InterruptedException {
    final WebTarget target = target().path("queryapi/queries");
    long failedQueries = metricsSvc.getTotalFailedQueries();
    System.out.println("%% " + failedQueries);
    LensConf conf = new LensConf();
    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
      "select ID from non_exist_table"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "execute"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), conf,
      MediaType.APPLICATION_XML_TYPE));
    final QueryHandle handle = target.request().post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE),
        new GenericType<LensAPIResult<QueryHandle>>() {
        }).getData();

    Assert.assertNotNull(handle);

    LensQuery ctx = target.path(handle.toString()).queryParam("sessionid", lensSessionId).request()
      .get(LensQuery.class);
    QueryStatus stat = ctx.getStatus();
    while (!stat.finished()) {
      ctx = target.path(handle.toString()).queryParam("sessionid", lensSessionId).request().get(LensQuery.class);
      stat = ctx.getStatus();
      System.out.println("%% query " + ctx.getQueryHandle() + " status:" + stat);
      Thread.sleep(1000);
    }

    assertTrue(ctx.getSubmissionTime() > 0);
    assertEquals(ctx.getLaunchTime(), 0);
    assertEquals(ctx.getDriverStartTime(), 0);
    assertEquals(ctx.getDriverFinishTime(), 0);
    assertTrue(ctx.getFinishTime() > 0);
    Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.FAILED);
    Assert.assertTrue(metricsSvc.getTotalFailedQueries() >= failedQueries + 1);
  }

  // test with execute async post, get all queries, get query context,
  // get wrong uuid query

  /**
   * Test queries api.
   *
   * @throws InterruptedException the interrupted exception
   */
  @Test
  public void testQueriesAPI() throws InterruptedException {
    // test post execute op
    final WebTarget target = target().path("queryapi/queries");
    LensConf conf = new LensConf();
    conf.addProperty("hive.exec.driver.run.hooks", TestHiveDriver.FailHook.class.getCanonicalName());
    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "select ID from " + TEST_TABLE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "execute"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), conf,
      MediaType.APPLICATION_XML_TYPE));

    long queuedQueries = metricsSvc.getQueuedQueries();
    long runningQueries = metricsSvc.getRunningQueries();
    long finishedQueries = metricsSvc.getFinishedQueries();

    final QueryHandle handle = target.request().post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE),
        new GenericType<LensAPIResult<QueryHandle>>() {
        }).getData();

    Assert.assertNotNull(handle);

    // Get all queries
    // XML
    List<QueryHandle> allQueriesXML = target.queryParam("sessionid", lensSessionId).request(MediaType.APPLICATION_XML)
      .get(new GenericType<List<QueryHandle>>() {});
    Assert.assertTrue(allQueriesXML.size() >= 1);

    // JSON
    // List<QueryHandle> allQueriesJSON = target.request(
    // MediaType.APPLICATION_JSON).get(new GenericType<List<QueryHandle>>() {
    // });
    // Assert.assertEquals(allQueriesJSON.size(), 1);
    // JAXB
    List<QueryHandle> allQueries = (List<QueryHandle>) target.queryParam("sessionid", lensSessionId).request()
      .get(new GenericType<List<QueryHandle>>() {
      });
    Assert.assertTrue(allQueries.size() >= 1);
    Assert.assertTrue(allQueries.contains(handle));

    // Get query
    // Invocation.Builder builderjson = target.path(handle.toString()).request(MediaType.APPLICATION_JSON);
    // String responseJSON = builderjson.get(String.class);
    // System.out.println("query JSON:" + responseJSON);
    String queryXML = target.path(handle.toString()).queryParam("sessionid", lensSessionId)
      .request(MediaType.APPLICATION_XML).get(String.class);
    System.out.println("query XML:" + queryXML);

    Response response = target.path(handle.toString() + "001").queryParam("sessionid", lensSessionId).request().get();
    Assert.assertEquals(response.getStatus(), 404);

    LensQuery ctx = target.path(handle.toString()).queryParam("sessionid", lensSessionId).request()
      .get(LensQuery.class);
    // Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.QUEUED);

    // wait till the query finishes
    QueryStatus stat = ctx.getStatus();
    while (!stat.finished()) {
      ctx = target.path(handle.toString()).queryParam("sessionid", lensSessionId).request().get(LensQuery.class);
      stat = ctx.getStatus();
      switch (stat.getStatus()) {
      case RUNNING:
        assertEquals(metricsSvc.getRunningQueries(), runningQueries + 1);
        break;
      case QUEUED:
        assertEquals(metricsSvc.getQueuedQueries(), queuedQueries + 1);
        break;
      default: // nothing
      }
      Thread.sleep(1000);
    }
    assertTrue(ctx.getSubmissionTime() > 0);
    assertTrue(ctx.getFinishTime() > 0);
    Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.FAILED);

    // Update conf for query
    final FormDataMultiPart confpart = new FormDataMultiPart();
    conf = new LensConf();
    conf.addProperty("my.property", "myvalue");
    confpart.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    confpart.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), conf,
      MediaType.APPLICATION_XML_TYPE));
    APIResult updateConf = target.path(handle.toString()).request()
      .put(Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE), APIResult.class);
    Assert.assertEquals(updateConf.getStatus(), APIResult.Status.FAILED);
  }

  // Test explain query

  /**
   * Test explain query.
   *
   * @throws InterruptedException the interrupted exception
   */
  @Test
  public void testExplainQuery() throws InterruptedException {
    final WebTarget target = target().path("queryapi/queries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "select ID from " + TEST_TABLE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "explain"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), new LensConf(),
      MediaType.APPLICATION_XML_TYPE));

    final QueryPlan plan = target.request()
      .post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE),
          new GenericType<LensAPIResult<QueryPlan>>() {}).getData();
    Assert.assertEquals(plan.getTablesQueried().size(), 1);
    Assert.assertTrue(plan.getTablesQueried().get(0).endsWith(TEST_TABLE.toLowerCase()));
    Assert.assertNull(plan.getPrepareHandle());

    // Test explain and prepare
    final WebTarget ptarget = target().path("queryapi/preparedqueries");

    final FormDataMultiPart mp2 = new FormDataMultiPart();
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
      "select ID from " + TEST_TABLE));
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "explain_and_prepare"));
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), new LensConf(),
      MediaType.APPLICATION_XML_TYPE));

    final QueryPlan plan2 = ptarget.request().post(Entity.entity(mp2, MediaType.MULTIPART_FORM_DATA_TYPE),
      QueryPlan.class);
    Assert.assertEquals(plan2.getTablesQueried().size(), 1);
    Assert.assertTrue(plan2.getTablesQueried().get(0).endsWith(TEST_TABLE.toLowerCase()));
    Assert.assertNotNull(plan2.getPrepareHandle());
  }

  // Test explain failure

  /**
   * Test explain failure.
   *
   * @throws InterruptedException the interrupted exception
   */
  @Test
  public void testExplainFailure() throws InterruptedException {
    final WebTarget target = target().path("queryapi/queries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
      "select NO_ID from " + TEST_TABLE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "explain"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), new LensConf(),
      MediaType.APPLICATION_XML_TYPE));

    final QueryPlan plan = target.request()
      .post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE),
          new GenericType<LensAPIResult<QueryPlan>>() {}).getData();
    Assert.assertTrue(plan.isError());
    Assert.assertNotNull(plan.getErrorMsg());
    Assert.assertTrue(plan.getErrorMsg()
        .contains("Invalid table alias or column reference 'NO_ID': " + "(possible column names are: id, idstr)"));

    // Test explain and prepare
    final WebTarget ptarget = target().path("queryapi/preparedqueries");

    final FormDataMultiPart mp2 = new FormDataMultiPart();
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "select NO_ID from "
      + TEST_TABLE));
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "explain_and_prepare"));
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), new LensConf(),
      MediaType.APPLICATION_XML_TYPE));

    final QueryPlan plan2 = ptarget.request().post(Entity.entity(mp2, MediaType.MULTIPART_FORM_DATA_TYPE),
      QueryPlan.class);
    Assert.assertTrue(plan2.isError());
    Assert.assertNotNull(plan2.getErrorMsg());
    Assert.assertNull(plan2.getPrepareHandle());
    Assert.assertTrue(plan2.getErrorMsg().contains("Invalid table alias or column reference 'NO_ID': "
      + "(possible column names are: id, idstr)"));
  }

  // post to preparedqueries
  // get all prepared queries
  // get a prepared query
  // update a prepared query
  // post to prepared query multiple times
  // delete a prepared query

  /**
   * Test prepare query.
   *
   * @throws InterruptedException the interrupted exception
   */
  @Test
  public void testPrepareQuery() throws InterruptedException {
    final WebTarget target = target().path("queryapi/preparedqueries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "select ID from " + TEST_TABLE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "prepare"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("queryName").build(), "testQuery1"));

    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), new LensConf(),
      MediaType.APPLICATION_XML_TYPE));

    final QueryPrepareHandle pHandle = target.request().post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE),
      QueryPrepareHandle.class);

    // Get all prepared queries
    List<QueryPrepareHandle> allQueries = (List<QueryPrepareHandle>) target.queryParam("sessionid", lensSessionId)
      .queryParam("queryName", "testQuery1").request().get(new GenericType<List<QueryPrepareHandle>>() {
      });
    Assert.assertTrue(allQueries.size() >= 1);
    Assert.assertTrue(allQueries.contains(pHandle));

    LensPreparedQuery ctx = target.path(pHandle.toString()).queryParam("sessionid", lensSessionId).request()
      .get(LensPreparedQuery.class);
    Assert.assertTrue(ctx.getUserQuery().equalsIgnoreCase("select ID from " + TEST_TABLE));
    Assert.assertTrue(ctx.getDriverQuery().equalsIgnoreCase("select ID from " + TEST_TABLE));
    Assert.assertEquals(ctx.getSelectedDriverClassName(),
      org.apache.lens.driver.hive.HiveDriver.class.getCanonicalName());
    Assert.assertNull(ctx.getConf().getProperties().get("my.property"));

    // Update conf for prepared query
    final FormDataMultiPart confpart = new FormDataMultiPart();
    LensConf conf = new LensConf();
    conf.addProperty("my.property", "myvalue");
    confpart.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    confpart.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), conf,
      MediaType.APPLICATION_XML_TYPE));
    APIResult updateConf = target.path(pHandle.toString()).request()
      .put(Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE), APIResult.class);
    Assert.assertEquals(updateConf.getStatus(), APIResult.Status.SUCCEEDED);

    ctx = target.path(pHandle.toString()).queryParam("sessionid", lensSessionId).request().get(LensPreparedQuery.class);
    Assert.assertEquals(ctx.getConf().getProperties().get("my.property"), "myvalue");

    QueryHandle handle1 = target.path(pHandle.toString()).request()
      .post(Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);

    // Override query name
    confpart.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("queryName").build(), "testQueryName2"));
    // do post once again
    QueryHandle handle2 = target.path(pHandle.toString()).request()
      .post(Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);
    Assert.assertNotEquals(handle1, handle2);

    LensQuery ctx1 = target().path("queryapi/queries").path(handle1.toString()).queryParam("sessionid", lensSessionId)
      .request().get(LensQuery.class);
    Assert.assertEquals(ctx1.getQueryName().toLowerCase(), "testquery1");
    // wait till the query finishes
    QueryStatus stat = ctx1.getStatus();
    while (!stat.finished()) {
      ctx1 = target().path("queryapi/queries").path(handle1.toString()).queryParam("sessionid", lensSessionId)
        .request().get(LensQuery.class);
      stat = ctx1.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx1.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    LensQuery ctx2 = target().path("queryapi/queries").path(handle2.toString()).queryParam("sessionid", lensSessionId)
      .request().get(LensQuery.class);
    Assert.assertNotNull(ctx2);
    Assert.assertEquals(ctx2.getQueryName().toLowerCase(), "testqueryname2");
    // wait till the query finishes
    stat = ctx2.getStatus();
    while (!stat.finished()) {
      ctx2 = target().path("queryapi/queries").path(handle1.toString()).queryParam("sessionid", lensSessionId)
        .request().get(LensQuery.class);
      stat = ctx2.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx1.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    // destroy prepared
    APIResult result = target.path(pHandle.toString()).queryParam("sessionid", lensSessionId).request()
      .delete(APIResult.class);
    Assert.assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

    // Post on destroyed query
    Response response = target.path(pHandle.toString()).request()
      .post(Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE), Response.class);
    Assert.assertEquals(response.getStatus(), 404);
  }

  /**
   * Test explain and prepare query.
   *
   * @throws InterruptedException the interrupted exception
   */
  @Test
  public void testExplainAndPrepareQuery() throws InterruptedException {
    final WebTarget target = target().path("queryapi/preparedqueries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "select ID from " + TEST_TABLE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "explain_and_prepare"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), new LensConf(),
      MediaType.APPLICATION_XML_TYPE));

    final QueryPlan plan = target.request()
      .post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryPlan.class);
    Assert.assertEquals(plan.getTablesQueried().size(), 1);
    Assert.assertTrue(plan.getTablesQueried().get(0).endsWith(TEST_TABLE.toLowerCase()));
    Assert.assertNotNull(plan.getPrepareHandle());

    LensPreparedQuery ctx = target.path(plan.getPrepareHandle().toString()).queryParam("sessionid", lensSessionId)
      .request().get(LensPreparedQuery.class);
    Assert.assertTrue(ctx.getUserQuery().equalsIgnoreCase("select ID from " + TEST_TABLE));
    Assert.assertTrue(ctx.getDriverQuery().equalsIgnoreCase("select ID from " + TEST_TABLE));
    Assert.assertEquals(ctx.getSelectedDriverClassName(),
        org.apache.lens.driver.hive.HiveDriver.class.getCanonicalName());
    Assert.assertNull(ctx.getConf().getProperties().get("my.property"));

    // Update conf for prepared query
    final FormDataMultiPart confpart = new FormDataMultiPart();
    LensConf conf = new LensConf();
    conf.addProperty("my.property", "myvalue");
    confpart.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    confpart.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), conf,
        MediaType.APPLICATION_XML_TYPE));
    APIResult updateConf = target.path(plan.getPrepareHandle().toString()).request()
      .put(Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE), APIResult.class);
    Assert.assertEquals(updateConf.getStatus(), APIResult.Status.SUCCEEDED);

    ctx = target.path(plan.getPrepareHandle().toString()).queryParam("sessionid", lensSessionId).request()
      .get(LensPreparedQuery.class);
    Assert.assertEquals(ctx.getConf().getProperties().get("my.property"), "myvalue");

    QueryHandle handle1 = target.path(plan.getPrepareHandle().toString()).request()
      .post(Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);

    // do post once again
    QueryHandle handle2 = target.path(plan.getPrepareHandle().toString()).request()
      .post(Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);
    Assert.assertNotEquals(handle1, handle2);

    LensQuery ctx1 = target().path("queryapi/queries").path(handle1.toString()).queryParam("sessionid", lensSessionId)
      .request().get(LensQuery.class);
    // wait till the query finishes
    QueryStatus stat = ctx1.getStatus();
    while (!stat.finished()) {
      ctx1 = target().path("queryapi/queries").path(handle1.toString()).queryParam("sessionid", lensSessionId)
        .request().get(LensQuery.class);
      stat = ctx1.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx1.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    LensQuery ctx2 = target().path("queryapi/queries").path(handle2.toString()).queryParam("sessionid", lensSessionId)
      .request().get(LensQuery.class);
    // wait till the query finishes
    stat = ctx2.getStatus();
    while (!stat.finished()) {
      ctx2 = target().path("queryapi/queries").path(handle1.toString()).queryParam("sessionid", lensSessionId)
        .request().get(LensQuery.class);
      stat = ctx2.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx1.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    // destroy prepared
    APIResult result = target.path(plan.getPrepareHandle().toString()).queryParam("sessionid", lensSessionId).request()
      .delete(APIResult.class);
    Assert.assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

    // Post on destroyed query
    Response response = target.path(plan.getPrepareHandle().toString()).request()
      .post(Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE), Response.class);
    Assert.assertEquals(response.getStatus(), 404);

  }

  // test with execute async post, get query, get results
  // test cancel query

  /**
   * Test execute async.
   *
   * @throws InterruptedException the interrupted exception
   * @throws IOException          Signals that an I/O exception has occurred.
   */
  @Test
  public void testExecuteAsync() throws InterruptedException, IOException {
    // test post execute op
    final WebTarget target = target().path("queryapi/queries");

    long queuedQueries = metricsSvc.getQueuedQueries();
    long runningQueries = metricsSvc.getRunningQueries();

    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "select ID, IDSTR from "
      + TEST_TABLE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "execute"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), new LensConf(),
      MediaType.APPLICATION_XML_TYPE));
    final QueryHandle handle = target.request().post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE),
        new GenericType<LensAPIResult<QueryHandle>>() {}).getData();

    Assert.assertNotNull(handle);

    // Get query
    LensQuery ctx = target.path(handle.toString()).queryParam("sessionid", lensSessionId).request()
      .get(LensQuery.class);
    Assert.assertTrue(ctx.getStatus().getStatus().equals(Status.QUEUED)
      || ctx.getStatus().getStatus().equals(Status.LAUNCHED) || ctx.getStatus().getStatus().equals(Status.RUNNING)
      || ctx.getStatus().getStatus().equals(Status.SUCCESSFUL));

    // wait till the query finishes
    QueryStatus stat = ctx.getStatus();
    while (!stat.finished()) {
      ctx = target.path(handle.toString()).queryParam("sessionid", lensSessionId).request().get(LensQuery.class);
      stat = ctx.getStatus();
      switch (stat.getStatus()) {
      case RUNNING:
        assertEquals(metricsSvc.getRunningQueries(), runningQueries + 1,
          "Asserting queries for " + ctx.getQueryHandle());
        break;
      case QUEUED:
        assertEquals(metricsSvc.getQueuedQueries(), queuedQueries + 1);
        break;
      default: // nothing
      }
      Thread.sleep(1000);
    }
    assertTrue(ctx.getSubmissionTime() > 0);
    assertTrue(ctx.getLaunchTime() > 0);
    assertTrue(ctx.getDriverStartTime() > 0);
    assertTrue(ctx.getDriverFinishTime() > 0);
    assertTrue(ctx.getFinishTime() > 0);
    Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    validatePersistedResult(handle, target(), lensSessionId, new String[][]{{"ID", "INT"}, {"IDSTR", "STRING"}}, true);

    // test cancel query
    final QueryHandle handle2 = target.request().post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE),
        new GenericType<LensAPIResult<QueryHandle>>() {}).getData();

    Assert.assertNotNull(handle2);
    APIResult result = target.path(handle2.toString()).queryParam("sessionid", lensSessionId).request()
      .delete(APIResult.class);
    // cancel would fail query is already successful
    LensQuery ctx2 = target.path(handle2.toString()).queryParam("sessionid", lensSessionId).request()
      .get(LensQuery.class);
    if (result.getStatus().equals(APIResult.Status.FAILED)) {
      Assert.assertEquals(ctx2.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL,
        "cancel failed without query having been succeeded");
    } else if (result.getStatus().equals(APIResult.Status.SUCCEEDED)) {
      Assert.assertEquals(ctx2.getStatus().getStatus(), QueryStatus.Status.CANCELED,
        "cancel succeeded but query wasn't cancelled");
    } else {
      Assert.fail("unexpected cancel status: " + result.getStatus());
    }

    // Test http download end point
    log.info("Starting httpendpoint test");
    final FormDataMultiPart mp3 = new FormDataMultiPart();
    mp3.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp3.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "select ID, IDSTR from "
      + TEST_TABLE));
    mp3.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "execute"));
    LensConf conf = new LensConf();
    conf.addProperty(LensConfConstants.QUERY_PERSISTENT_RESULT_SET, "true");

    mp3.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), conf,
      MediaType.APPLICATION_XML_TYPE));
    final QueryHandle handle3 = target.request().post(Entity.entity(mp3, MediaType.MULTIPART_FORM_DATA_TYPE),
        new GenericType<LensAPIResult<QueryHandle>>() {}).getData();

    // Get query
    ctx = target.path(handle3.toString()).queryParam("sessionid", lensSessionId).request().get(LensQuery.class);
    // wait till the query finishes
    stat = ctx.getStatus();
    while (!stat.finished()) {
      ctx = target.path(handle3.toString()).queryParam("sessionid", lensSessionId).request().get(LensQuery.class);
      stat = ctx.getStatus();
      Thread.sleep(1000);
    }
    validateHttpEndPoint(target(), null, handle3, null);
  }

  /**
   * Validate persisted result.
   *
   * @param handle        the handle
   * @param parent        the parent
   * @param lensSessionId the lens session id
   * @param isDir         the is dir
   * @throws IOException Signals that an I/O exception has occurred.
   */
  static void validatePersistedResult(QueryHandle handle, WebTarget parent, LensSessionHandle lensSessionId,
    String[][] schema, boolean isDir) throws IOException {
    final WebTarget target = parent.path("queryapi/queries");
    // fetch results
    validateResultSetMetadata(handle, "",
      schema,
      parent, lensSessionId);

    String presultset = target.path(handle.toString()).path("resultset").queryParam("sessionid", lensSessionId)
      .request().get(String.class);
    System.out.println("PERSISTED RESULT:" + presultset);

    PersistentQueryResult resultset = target.path(handle.toString()).path("resultset")
      .queryParam("sessionid", lensSessionId).request().get(PersistentQueryResult.class);
    validatePersistentResult(resultset, handle, isDir);

    if (isDir) {
      validNotFoundForHttpResult(parent, lensSessionId, handle);
    }
  }

  /**
   * Read result set.
   *
   * @param resultset the resultset
   * @param handle    the handle
   * @param isDir     the is dir
   * @return the list
   * @throws IOException Signals that an I/O exception has occurred.
   */
  public static List<String> readResultSet(PersistentQueryResult resultset, QueryHandle handle, boolean isDir)
    throws IOException {
    Assert.assertTrue(resultset.getPersistedURI().contains(handle.toString()));
    Path actualPath = new Path(resultset.getPersistedURI());
    FileSystem fs = actualPath.getFileSystem(new Configuration());
    List<String> actualRows = new ArrayList<String>();
    if (fs.getFileStatus(actualPath).isDir()) {
      Assert.assertTrue(isDir);
      for (FileStatus fstat : fs.listStatus(actualPath)) {
        addRowsFromFile(actualRows, fs, fstat.getPath());
      }
    } else {
      Assert.assertFalse(isDir);
      addRowsFromFile(actualRows, fs, actualPath);
    }
    return actualRows;
  }

  /**
   * Adds the rows from file.
   *
   * @param actualRows the actual rows
   * @param fs         the fs
   * @param path       the path
   * @throws IOException Signals that an I/O exception has occurred.
   */
  static void addRowsFromFile(List<String> actualRows, FileSystem fs, Path path) throws IOException {
    FSDataInputStream in = fs.open(path);
    BufferedReader br = null;
    try {
      br = new BufferedReader(new InputStreamReader(in));
      String line = "";

      while ((line = br.readLine()) != null) {
        actualRows.add(line);
      }
    } finally {
      if (br != null) {
        br.close();
      }
      if (in != null) {
        in.close();
      }
    }
  }

  /**
   * Validate persistent result.
   *
   * @param resultset the resultset
   * @param handle    the handle
   * @param isDir     the is dir
   * @throws IOException Signals that an I/O exception has occurred.
   */
  static void validatePersistentResult(PersistentQueryResult resultset, QueryHandle handle, boolean isDir)
    throws IOException {
    List<String> actualRows = readResultSet(resultset, handle, isDir);
    validatePersistentResult(actualRows);
  }

  static void validatePersistentResult(List<String> actualRows) {
    String[] expected1 = new String[]{
      "1one",
      "\\Ntwo123item1item2",
      "3\\Nitem1item2",
      "\\N\\N",
      "5nothing",
    };
    String[] expected2 = new String[]{
      "1one[][]",
      "\\Ntwo[1,2,3][\"item1\",\"item2\"]",
      "3\\N[][\"item1\",\"item2\"]",
      "\\N\\N[][]",
      "5[][\"nothing\"]",
    };
    for (int i = 0; i < actualRows.size(); i++) {
      Assert.assertEquals(
        expected1[i].indexOf(actualRows.get(i)) == 0 || expected2[i].indexOf(actualRows.get(i)) == 0, true);
    }
  }

  /**
   * Validate http end point.
   *
   * @param parent        the parent
   * @param lensSessionId the lens session id
   * @param handle        the handle
   * @param redirectUrl   the redirect url
   * @throws IOException Signals that an I/O exception has occurred.
   */
  static void validateHttpEndPoint(WebTarget parent, LensSessionHandle lensSessionId, QueryHandle handle,
    String redirectUrl) throws IOException {
    log.info("@@@ validateHttpEndPoint sessionid " + lensSessionId);
    Response response = parent.path("queryapi/queries/" + handle.toString() + "/httpresultset")
      .queryParam("sessionid", lensSessionId).request().get();

    Assert.assertTrue(response.getHeaderString("content-disposition").contains(handle.toString()));

    if (redirectUrl == null) {
      InputStream in = (InputStream) response.getEntity();
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      IOUtils.copyBytes(in, bos, new Configuration());
      bos.close();
      in.close();

      String result = new String(bos.toByteArray());
      List<String> actualRows = Arrays.asList(result.split("\n"));
      validatePersistentResult(actualRows);
    } else {
      Assert.assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
      Assert.assertTrue(response.getHeaderString("Location").contains(redirectUrl));
    }
  }

  /**
   * Valid not found for http result.
   *
   * @param parent        the parent
   * @param lensSessionId the lens session id
   * @param handle        the handle
   */
  static void validNotFoundForHttpResult(WebTarget parent, LensSessionHandle lensSessionId, QueryHandle handle) {
    try {
      Response response = parent.path("queryapi/queries/" + handle.toString() + "/httpresultset")
        .queryParam("sessionid", lensSessionId).request().get();
      if (Response.Status.NOT_FOUND.getStatusCode() != response.getStatus()) {
        Assert.fail("Expected not found excepiton, but got:" + response.getStatus());
      }
      Assert.assertEquals(response.getStatus(), Response.Status.NOT_FOUND.getStatusCode());
    } catch (NotFoundException e) {
      // expected
      log.error("Resource not found.", e);
    }

  }

  // test with execute async post, get query, get results
  // test cancel query

  /**
   * Test execute async in memory result.
   *
   * @throws InterruptedException the interrupted exception
   * @throws IOException          Signals that an I/O exception has occurred.
   */
  @Test
  public void testExecuteAsyncInMemoryResult() throws InterruptedException, IOException {
    // test post execute op
    final WebTarget target = target().path("queryapi/queries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    LensConf conf = new LensConf();
    conf.addProperty(LensConfConstants.QUERY_PERSISTENT_RESULT_INDRIVER, "false");
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "select ID, IDSTR from "
      + TEST_TABLE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "execute"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), conf,
      MediaType.APPLICATION_XML_TYPE));
    final QueryHandle handle = target.request().post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE),
        new GenericType<LensAPIResult<QueryHandle>>() {}).getData();

    Assert.assertNotNull(handle);

    // Get query
    LensQuery ctx = target.path(handle.toString()).queryParam("sessionid", lensSessionId).request()
      .get(LensQuery.class);
    Assert.assertTrue(ctx.getStatus().getStatus().equals(Status.QUEUED)
      || ctx.getStatus().getStatus().equals(Status.LAUNCHED) || ctx.getStatus().getStatus().equals(Status.RUNNING)
      || ctx.getStatus().getStatus().equals(Status.SUCCESSFUL));

    // wait till the query finishes
    QueryStatus stat = ctx.getStatus();
    while (!stat.finished()) {
      ctx = target.path(handle.toString()).queryParam("sessionid", lensSessionId).request().get(LensQuery.class);
      stat = ctx.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    // fetch results
    validateResultSetMetadata(handle, "",
      new String[][]{{"ID", "INT"}, {"IDSTR", "STRING"}},
      target(), lensSessionId);


    InMemoryQueryResult resultset = target.path(handle.toString()).path("resultset")
      .queryParam("sessionid", lensSessionId).request().get(InMemoryQueryResult.class);
    validateInmemoryResult(resultset);

    validNotFoundForHttpResult(target(), lensSessionId, handle);
  }

  /**
   * Test execute async temp table.
   *
   * @throws InterruptedException the interrupted exception
   * @throws IOException          Signals that an I/O exception has occurred.
   */
  @Test
  public void testExecuteAsyncTempTable() throws InterruptedException, IOException {
    // test post execute op
    final WebTarget target = target().path("queryapi/queries");

    final FormDataMultiPart drop = new FormDataMultiPart();
    LensConf conf = new LensConf();
    conf.addProperty(LensConfConstants.QUERY_PERSISTENT_RESULT_INDRIVER, "false");
    drop.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    drop.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
      "drop table if exists temp_output"));
    drop.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "execute"));
    drop.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), conf,
      MediaType.APPLICATION_XML_TYPE));
    final QueryHandle dropHandle = target.request().post(Entity.entity(drop, MediaType.MULTIPART_FORM_DATA_TYPE),
        new GenericType<LensAPIResult<QueryHandle>>() {}).getData();

    Assert.assertNotNull(dropHandle);

    // Get query
    LensQuery ctx = target.path(dropHandle.toString()).queryParam("sessionid", lensSessionId).request()
      .get(LensQuery.class);
    Assert.assertTrue(ctx.getStatus().getStatus().equals(Status.QUEUED)
      || ctx.getStatus().getStatus().equals(Status.LAUNCHED) || ctx.getStatus().getStatus().equals(Status.RUNNING)
      || ctx.getStatus().getStatus().equals(Status.SUCCESSFUL));

    // wait till the query finishes
    QueryStatus stat = ctx.getStatus();
    while (!stat.finished()) {
      ctx = target.path(dropHandle.toString()).queryParam("sessionid", lensSessionId).request().get(LensQuery.class);
      stat = ctx.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    final FormDataMultiPart mp = new FormDataMultiPart();
    conf = new LensConf();
    conf.addProperty(LensConfConstants.QUERY_PERSISTENT_RESULT_INDRIVER, "false");
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
      "create table temp_output as select ID, IDSTR from " + TEST_TABLE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "execute"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), conf,
      MediaType.APPLICATION_XML_TYPE));
    final QueryHandle handle = target.request().post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE),
        new GenericType<LensAPIResult<QueryHandle>>() {}).getData();

    Assert.assertNotNull(handle);

    // Get query
    ctx = target.path(handle.toString()).queryParam("sessionid", lensSessionId).request().get(LensQuery.class);
    Assert.assertTrue(ctx.getStatus().getStatus().equals(Status.QUEUED)
      || ctx.getStatus().getStatus().equals(Status.LAUNCHED) || ctx.getStatus().getStatus().equals(Status.RUNNING)
      || ctx.getStatus().getStatus().equals(Status.SUCCESSFUL));

    // wait till the query finishes
    stat = ctx.getStatus();
    while (!stat.finished()) {
      ctx = target.path(handle.toString()).queryParam("sessionid", lensSessionId).request().get(LensQuery.class);
      stat = ctx.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    String select = "SELECT * FROM temp_output";
    final FormDataMultiPart fetch = new FormDataMultiPart();
    fetch.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    fetch.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), select));
    fetch.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "execute"));
    fetch.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), conf,
      MediaType.APPLICATION_XML_TYPE));
    final QueryHandle handle2 = target.request().post(Entity.entity(fetch, MediaType.MULTIPART_FORM_DATA_TYPE),
        new GenericType<LensAPIResult<QueryHandle>>() {}).getData();

    Assert.assertNotNull(handle2);

    // Get query
    ctx = target.path(handle2.toString()).queryParam("sessionid", lensSessionId).request().get(LensQuery.class);

    // wait till the query finishes
    stat = ctx.getStatus();
    while (!stat.finished()) {
      ctx = target.path(handle2.toString()).queryParam("sessionid", lensSessionId).request().get(LensQuery.class);
      stat = ctx.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    // fetch results
    validateResultSetMetadata(handle2, "temp_output.", new String[][]{{"ID", "INT"}, {"IDSTR", "STRING"}},
      target(), lensSessionId);

    InMemoryQueryResult resultset = target.path(handle2.toString()).path("resultset")
      .queryParam("sessionid", lensSessionId).request().get(InMemoryQueryResult.class);
    validateInmemoryResult(resultset);
  }

  /**
   * Validate result set metadata.
   *
   * @param handle        the handle
   * @param parent        the parent
   * @param lensSessionId the lens session id
   */
  static void validateResultSetMetadata(QueryHandle handle, WebTarget parent, LensSessionHandle lensSessionId) {
    validateResultSetMetadata(handle, "",
      new String[][]{{"ID", "INT"}, {"IDSTR", "STRING"}, {"IDARR", "ARRAY"}, {"IDSTRARR", "ARRAY"}},
      parent, lensSessionId);
  }

  /**
   * Validate result set metadata.
   *
   * @param handle         the handle
   * @param outputTablePfx the output table pfx
   * @param parent         the parent
   * @param lensSessionId  the lens session id
   */
  static void validateResultSetMetadata(QueryHandle handle, String outputTablePfx, String[][] columns, WebTarget parent,
    LensSessionHandle lensSessionId) {
    final WebTarget target = parent.path("queryapi/queries");

    QueryResultSetMetadata metadata = target.path(handle.toString()).path("resultsetmetadata")
      .queryParam("sessionid", lensSessionId).request().get(QueryResultSetMetadata.class);
    Assert.assertEquals(metadata.getColumns().size(), columns.length);
    for (int i = 0; i < columns.length; i++) {
      assertTrue(
        metadata.getColumns().get(i).getName().toLowerCase().equals(outputTablePfx + columns[i][0].toLowerCase())
          || metadata.getColumns().get(i).getName().toLowerCase().equals(columns[i][0].toLowerCase())
      );
      assertEquals(columns[i][1].toLowerCase(), metadata.getColumns().get(i).getType().name().toLowerCase());
    }
  }

  /**
   * Validate inmemory result.
   *
   * @param resultset the resultset
   */
  private void validateInmemoryResult(InMemoryQueryResult resultset) {
    Assert.assertEquals(resultset.getRows().size(), 5);
    Assert.assertEquals(resultset.getRows().get(0).getValues().get(0), 1);
    Assert.assertEquals((String) resultset.getRows().get(0).getValues().get(1), "one");

    Assert.assertNull(resultset.getRows().get(1).getValues().get(0));
    Assert.assertEquals((String) resultset.getRows().get(1).getValues().get(1), "two");

    Assert.assertEquals(resultset.getRows().get(2).getValues().get(0), 3);
    Assert.assertNull(resultset.getRows().get(2).getValues().get(1));

    Assert.assertNull(resultset.getRows().get(3).getValues().get(0));
    Assert.assertNull(resultset.getRows().get(3).getValues().get(1));
    Assert.assertEquals(resultset.getRows().get(4).getValues().get(0), 5);
    Assert.assertEquals(resultset.getRows().get(4).getValues().get(1), "");
  }

  // test execute with timeout, fetch results
  // cancel the query with execute_with_timeout

  /**
   * Test execute with timeout query.
   *
   * @throws IOException          Signals that an I/O exception has occurred.
   * @throws InterruptedException the interrupted exception
   */
  @Test
  public void testExecuteWithTimeoutQuery() throws IOException, InterruptedException {
    final WebTarget target = target().path("queryapi/queries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "select ID, IDSTR from "
      + TEST_TABLE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "execute_with_timeout"));
    // set a timeout value enough for tests
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("timeoutmillis").build(), "300000"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), new LensConf(),
      MediaType.APPLICATION_XML_TYPE));

    QueryHandleWithResultSet result = target.request().post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE),
        new GenericType<LensAPIResult<QueryHandleWithResultSet>>() {}).getData();
    Assert.assertNotNull(result.getQueryHandle());
    Assert.assertNotNull(result.getResult());
    validatePersistentResult((PersistentQueryResult) result.getResult(), result.getQueryHandle(), true);

    final FormDataMultiPart mp2 = new FormDataMultiPart();
    LensConf conf = new LensConf();
    conf.addProperty(LensConfConstants.QUERY_PERSISTENT_RESULT_INDRIVER, "false");
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "select ID, IDSTR from "
      + TEST_TABLE));
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "execute_with_timeout"));
    // set a timeout value enough for tests
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("timeoutmillis").build(), "300000"));
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), conf,
      MediaType.APPLICATION_XML_TYPE));

    result = target.request().post(Entity.entity(mp2, MediaType.MULTIPART_FORM_DATA_TYPE),
        new GenericType<LensAPIResult<QueryHandleWithResultSet>>() {}).getData();
    Assert.assertNotNull(result.getQueryHandle());
    Assert.assertNotNull(result.getResult());
    validateInmemoryResult((InMemoryQueryResult) result.getResult());
  }

  /**
   * Test execute with timeout query.
   *
   * @throws IOException          Signals that an I/O exception has occurred.
   * @throws InterruptedException the interrupted exception
   */
  @Test
  public void testExecuteWithTimeoutFailingQuery() throws IOException, InterruptedException {
    final WebTarget target = target().path("queryapi/queries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "select ID from nonexist"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "execute_with_timeout"));
    // set a timeout value enough for tests
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("timeoutmillis").build(), "300000"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), new LensConf(),
      MediaType.APPLICATION_XML_TYPE));

    QueryHandleWithResultSet result = target.request().post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE),
        new GenericType<LensAPIResult<QueryHandleWithResultSet>>() {}).getData();
    Assert.assertNotNull(result.getQueryHandle());
    Assert.assertNull(result.getResult());

    LensQuery ctx = target.path(result.getQueryHandle().toString()).queryParam("sessionid", lensSessionId).request()
      .get(LensQuery.class);
    Assert.assertEquals(ctx.getStatus().getStatus(), Status.FAILED);
  }

  /**
   * Test default config.
   *
   * @throws LensException the lens exception
   */
  @Test
  public void testDefaultConfig() throws LensException {
    LensConf queryConf = new LensConf();
    queryConf.addProperty("test.query.conf", "qvalue");
    Configuration conf = queryService.getLensConf(lensSessionId, queryConf);

    // session specific conf
    Assert.assertEquals(conf.get("test.session.key"), "svalue");
    // query specific conf
    Assert.assertEquals(conf.get("test.query.conf"), "qvalue");
    // lenssession default should be loaded
    Assert.assertNotNull(conf.get("lens.query.enable.persistent.resultset"));
    // lens site should be loaded
    Assert.assertEquals(conf.get("test.lens.site.key"), "gsvalue");
    // hive default variables should not be set
    Assert.assertNull(conf.get("hive.exec.local.scratchdir"));
    // hive site variables should not be set
    Assert.assertNull(conf.get("hive.metastore.warehouse.dir"));
    // core default should not be loaded
    Assert.assertNull(conf.get("fs.default.name"));

    // Test server config. Hive configs overriden should be set
    Assert.assertFalse(Boolean.parseBoolean(queryService.getHiveConf().get("hive.server2.log.redirection.enabled")));
    Assert.assertEquals(queryService.getHiveConf().get("hive.server2.query.log.dir"), "target/query-logs");

    final String query = "select ID from " + TEST_TABLE;
    QueryContext ctx = new QueryContext(query, null, queryConf, conf, queryService.getDrivers());
    Map<LensDriver, String> driverQueries = new HashMap<LensDriver, String>();
    for (LensDriver driver : queryService.getDrivers()) {
      driverQueries.put(driver, query);
    }
    ctx.setDriverQueries(driverQueries);

    // This still holds since current database is default
    Assert.assertEquals(queryService.getSession(lensSessionId).getCurrentDatabase(), "default");
    Assert.assertEquals(queryService.getSession(lensSessionId).getHiveConf().getClassLoader(), ctx.getConf()
      .getClassLoader());
    Assert.assertEquals(queryService.getSession(lensSessionId).getHiveConf().getClassLoader(),
      ctx.getDriverContext().getDriverConf(queryService.getDrivers().iterator().next()).getClassLoader());
    Assert.assertTrue(ctx.isDriverQueryExplicitlySet());
    for (LensDriver driver : queryService.getDrivers()) {
      Configuration dconf = ctx.getDriverConf(driver);
      Assert.assertEquals(dconf.get("test.session.key"), "svalue");
      // query specific conf
      Assert.assertEquals(dconf.get("test.query.conf"), "qvalue");
      // lenssession default should be loaded
      Assert.assertNotNull(dconf.get("lens.query.enable.persistent.resultset"));
      // lens site should be loaded
      Assert.assertEquals(dconf.get("test.lens.site.key"), "gsvalue");
      // hive default variables should not be set
      Assert.assertNull(conf.get("hive.exec.local.scratchdir"));
      // driver site should be loaded
      Assert.assertEquals(dconf.get("lens.driver.test.key"), "set");
      // core default should not be loaded
      Assert.assertNull(dconf.get("fs.default.name"));
    }
  }

  /**
   * Test estimate native query.
   *
   * @throws InterruptedException the interrupted exception
   */
  @Test
  public void testEstimateNativeQuery() throws InterruptedException {
    final WebTarget target = target().path("queryapi/queries");

    // estimate native query
    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "select ID from " + TEST_TABLE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "estimate"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), new LensConf(),
      MediaType.APPLICATION_XML_TYPE));

    final QueryCostTO result = target.request()
      .post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE),
          new GenericType<LensAPIResult<QueryCostTO>>() {}).getData();
    Assert.assertNotNull(result);
    Assert.assertEquals(result.getEstimatedExecTimeMillis(), null);
    Assert.assertEquals(result.getEstimatedResourceUsage(), Double.MAX_VALUE);
  }


  /**
   * Check if DB static jars get passed to Hive driver
   * @throws Exception
   */
  @Test
  public void testHiveDriverGetsDBJars() throws Exception {
    // Set DB to a db with static jars
    HiveSessionService sessionService = LensServices.get().getService(SessionService.NAME);

    // Open session with a DB which has static jars
    LensSessionHandle sessionHandle =
      sessionService.openSession("foo@localhost", "bar", LensTestUtil.DB_WITH_JARS, new HashMap<String, String>());

    // Add a jar in the session
    File testJarFile = new File("target/testjars/test2.jar");
    sessionService.addResourceToAllServices(sessionHandle, "jar", "file://" + testJarFile.getAbsolutePath());

    log.info("@@@ Opened session " + sessionHandle.getPublicId() + " with database " + LensTestUtil.DB_WITH_JARS);
    LensSessionImpl session = sessionService.getSession(sessionHandle);

    // Jars should be pending until query is run
    Assert.assertEquals(session.getPendingSessionResourcesForDatabase(LensTestUtil.DB_WITH_JARS).size(), 1);
    Assert.assertEquals(session.getPendingSessionResourcesForDatabase(LensTestUtil.DB_WITH_JARS_2).size(), 1);

    final String tableInDBWithJars = "testHiveDriverGetsDBJars";
    try {
      // First execute query on the session with db should load jars from DB
      LensTestUtil.createTable(tableInDBWithJars, target(), sessionHandle, "(ID INT, IDSTR STRING) "
        + "ROW FORMAT SERDE \"DatabaseJarSerde\"");

      boolean addedToHiveDriver = false;

      for (LensDriver driver : queryService.getDrivers()) {
        if (driver instanceof HiveDriver) {
          addedToHiveDriver =
            ((HiveDriver) driver).areDBResourcesAddedForSession(sessionHandle.getPublicId().toString(),
              LensTestUtil.DB_WITH_JARS);
        }
      }
      Assert.assertTrue(addedToHiveDriver);

      // Switch database
      log.info("@@@# database switch test");
      session.setCurrentDatabase(LensTestUtil.DB_WITH_JARS_2);
      LensTestUtil.createTable(tableInDBWithJars + "_2", target(), sessionHandle, "(ID INT, IDSTR STRING) "
        + "ROW FORMAT SERDE \"DatabaseJarSerde\"");

      // All db jars should have been added
      Assert.assertTrue(session.getDBResources(LensTestUtil.DB_WITH_JARS_2).isEmpty());
      Assert.assertTrue(session.getDBResources(LensTestUtil.DB_WITH_JARS).isEmpty());

      // All session resources must have been added to both DBs
      Assert.assertFalse(session.getLensSessionPersistInfo().getResources().isEmpty());
      for (LensSessionImpl.ResourceEntry resource : session.getLensSessionPersistInfo().getResources()) {
        Assert.assertTrue(resource.isAddedToDatabase(LensTestUtil.DB_WITH_JARS_2));
        Assert.assertTrue(resource.isAddedToDatabase(LensTestUtil.DB_WITH_JARS));
      }

      Assert.assertTrue(session.getPendingSessionResourcesForDatabase(LensTestUtil.DB_WITH_JARS).isEmpty());
      Assert.assertTrue(session.getPendingSessionResourcesForDatabase(LensTestUtil.DB_WITH_JARS_2).isEmpty());

    } finally {
      log.info("@@@ TEST_OVER");
      try {
        LensTestUtil.dropTable(tableInDBWithJars, target(), sessionHandle);
        LensTestUtil.dropTable(tableInDBWithJars + "_2", target(), sessionHandle);
      } catch (Throwable th) {
        log.error("Exception while dropping table.", th);
      }
      sessionService.closeSession(sessionHandle);
    }
  }

  @Test
  public void testRewriteFailure() {
    final WebTarget target = target().path("queryapi/queries");

    // estimate cube query which fails semantic analysis
    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
        MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "cube select ID from nonexist"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "estimate"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), new LensConf(),
        MediaType.APPLICATION_XML_TYPE));

    final Response response = target.request()
        .post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE));

    LensErrorTO expectedLensErrorTO = LensErrorTO.composedOf(LensCommonErrorCode.INTERNAL_SERVER_ERROR.getValue(),
        "Internal Server Error.", TestDataUtils.MOCK_STACK_TRACE);
    ErrorResponseExpectedData expectedData = new ErrorResponseExpectedData(INTERNAL_SERVER_ERROR, expectedLensErrorTO);

    expectedData.verify(response);
  }

  @Test
  public void testDriverEstimateSkippingForRewritefailure() throws LensException {
    Configuration conf = queryService.getLensConf(lensSessionId, new LensConf());
    QueryContext ctx = new QueryContext("cube select ID from nonexist", "user", new LensConf(), conf,
      queryService.getDrivers());
    for (LensDriver driver : queryService.getDrivers()) {
      ctx.setDriverRewriteError(driver, new LensException());
    }

    // All estimates should be skipped.
    Map<LensDriver, AbstractQueryContext.DriverEstimateRunnable> estimateRunnables = ctx.getDriverEstimateRunnables();
    for (LensDriver driver : estimateRunnables.keySet()) {
      estimateRunnables.get(driver).run();
      Assert.assertFalse(estimateRunnables.get(driver).isSucceeded(), driver + " estimate should have been skipped");
    }

    for (LensDriver driver : queryService.getDrivers()) {
      Assert.assertNull(ctx.getDriverQueryCost(driver));
    }
  }

  @Test
  public void testNonSelectQueriesWithPersistResult() throws InterruptedException {
    LensConf conf = new LensConf();
    conf.addProperty(LensConfConstants.QUERY_PERSISTENT_RESULT_INDRIVER, "true");
    String tblName = "testNonSelectQueriesWithPersistResult";
    LensTestUtil.dropTableWithConf(tblName, target(), lensSessionId, conf);
    conf.addProperty(LensConfConstants.QUERY_PERSISTENT_RESULT_SET, "true");
    LensTestUtil.dropTableWithConf(tblName, target(), lensSessionId, conf);
    conf.addProperty(LensConfConstants.QUERY_PERSISTENT_RESULT_INDRIVER, "false");
    LensTestUtil.dropTableWithConf(tblName, target(), lensSessionId, conf);
    conf.addProperty(LensConfConstants.QUERY_PERSISTENT_RESULT_SET, "false");
    LensTestUtil.dropTableWithConf(tblName, target(), lensSessionId, conf);
  }

  @Test
  public void testEstimateGauges() {
    final WebTarget target = target().path("queryapi/queries");

    LensConf conf = new LensConf();
    conf.addProperty(LensConfConstants.QUERY_METRIC_UNIQUE_ID_CONF_KEY, "TestQueryService-testEstimateGauges");
    // estimate native query
    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "select ID from " + TEST_TABLE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "estimate"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), conf,
      MediaType.APPLICATION_XML_TYPE));

    final QueryCostTO queryCostTO = target.request()
      .post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE),
          new GenericType<LensAPIResult<QueryCostTO>>() {
          }).getData();
    Assert.assertNotNull(queryCostTO);

    MetricRegistry reg = LensMetricsRegistry.getStaticRegistry();

    Assert.assertTrue(reg.getGauges().keySet().containsAll(Arrays.asList(
        "lens.MethodMetricGauge.TestQueryService-testEstimateGauges-DRIVER_SELECTION",
        "lens.MethodMetricGauge.TestQueryService-testEstimateGauges-HiveDriver-CUBE_REWRITE",
        "lens.MethodMetricGauge.TestQueryService-testEstimateGauges-HiveDriver-DRIVER_ESTIMATE",
        "lens.MethodMetricGauge.TestQueryService-testEstimateGauges-HiveDriver-RewriteUtil-rewriteQuery",
        "lens.MethodMetricGauge.TestQueryService-testEstimateGauges-JDBCDriver-CUBE_REWRITE",
        "lens.MethodMetricGauge.TestQueryService-testEstimateGauges-JDBCDriver-DRIVER_ESTIMATE",
        "lens.MethodMetricGauge.TestQueryService-testEstimateGauges-JDBCDriver-RewriteUtil-rewriteQuery",
        "lens.MethodMetricGauge.TestQueryService-testEstimateGauges-PARALLEL_ESTIMATE")),
      reg.getGauges().keySet().toString());
  }
  @Test
  public void testQueryRejection() throws InterruptedException, IOException {
    final WebTarget target = target().path("queryapi/queries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), lensSessionId,
      MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "blah select ID from "
      + TEST_TABLE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "execute"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), new LensConf(),
      MediaType.APPLICATION_XML_TYPE));

    Response response = target.request().post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE));
    Assert.assertEquals(response.getStatus(), 400);
  }
}
