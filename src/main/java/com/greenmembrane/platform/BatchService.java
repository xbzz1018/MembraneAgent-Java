package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BatchService {
  private final BusinessRepository repo;
  private final TaskService tasks;
  private final TaskDispatcher dispatcher;
  private final TransactionTemplate tx;
  public BatchService(BusinessRepository repo,TaskService tasks,TaskDispatcher dispatcher,TransactionTemplate tx) {
    this.repo=repo;this.tasks=tasks;this.dispatcher=dispatcher;this.tx=tx;
  }
  public Map<String,Object> submit(Identity who,String project,List<String> documentIds,String requestKey) {
    repo.project(who,project);
    repo.role(who,"BUSINESS","DESIGNER");
    if (documentIds==null || documentIds.isEmpty() || documentIds.size()>20
        || documentIds.stream().anyMatch(x -> x==null || x.isBlank())
        || requestKey==null || requestKey.length()<8 || requestKey.length()>128)
      throw error(HttpStatus.UNPROCESSABLE_ENTITY,"请选择1至20份资料并提供8至128字符的请求标识");
    var ids=documentIds.stream().distinct().sorted().toList();
    String hash=sha(json(ids));
    String batch=tx.execute(s -> {
      repo.required("SELECT id FROM mb_projects WHERE id=? FOR UPDATE",project);
      var prior=repo.one("SELECT * FROM mb_ingest_batches WHERE project_id=? AND request_key=?",project,requestKey);
      if (prior!=null) {
        if (!hash.equals(text(prior,"request_hash"))) throw error(HttpStatus.CONFLICT,"同一批次请求标识的资料列表不同");
        return text(prior,"id");
      }
      String id=id();
      repo.sql().update("INSERT INTO mb_ingest_batches VALUES(?,?,?,?,?,?)",id,project,requestKey,hash,who.userId(),now());
      for (String documentId:ids) {
        var doc=repo.document(who,documentId);
        if (!project.equals(text(doc,"project_id"))) throw error(HttpStatus.CONFLICT,"资料不属于该批次项目");
        var task=tasks.index(who,documentId);
        repo.sql().update("INSERT INTO mb_ingest_batch_items VALUES(?,?,?,?)",id,documentId,text(task,"id"),text(doc,"business_version"));
      }
      repo.event(project,who.userId(),"batch.submitted",id,hash);
      return id;
    });
    // The inline path is an explicit test harness; Rabbit publishing never occurs inside the transaction.
    for (var task:repo.sql().queryForList("SELECT t.* FROM mb_tasks t JOIN mb_ingest_batch_items i ON i.task_id=t.id WHERE i.batch_id=?",batch))
      dispatcher.start(task);
    return view(who,batch);
  }
  public Map<String,Object> view(Identity who,String id) {
    var batch=repo.required("SELECT * FROM mb_ingest_batches WHERE id=?",id);
    repo.project(who,text(batch,"project_id"));
    var items=repo.sql().queryForList(
        "SELECT i.document_id,i.business_version,t.id AS task_id,t.status,t.generation,t.attempt,t.last_error,d.name,"
        + "o.status AS delivery_status,o.last_error AS delivery_error"
        + " FROM mb_ingest_batch_items i JOIN mb_tasks t ON t.id=i.task_id JOIN mb_documents d ON d.id=i.document_id"
        + " LEFT JOIN mb_task_outbox o ON o.task_id=t.id"
        + " WHERE i.batch_id=? ORDER BY i.document_id",id);
    Map<String,Long> counts=new LinkedHashMap<>();
    for (String state:List.of("queued","running","completed","failed","cancelled")) counts.put(state,0L);
    for (var item:items) counts.merge(text(item,"status"),1L,Long::sum);
    var result=new LinkedHashMap<String,Object>();
    result.put("id",id);result.put("project_id",text(batch,"project_id"));
    result.put("total",items.size());result.put("counts",counts);result.put("items",items);
    long finished=counts.get("completed")+counts.get("failed")+counts.get("cancelled");
    result.put("finished",finished);
    result.put("status",finished==items.size() ? (counts.get("completed")==items.size() ? "completed" : "finished_with_errors") : "processing");
    return result;
  }
}
