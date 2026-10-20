package com.example.server.editor;
import com.example.server.editor.EditorModels.TimedCue;
import com.example.server.editor.SmartSelectionModels.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class SmartSelectionValidator {
 private final ObjectMapper json;
 public SmartSelectionValidator(ObjectMapper json){this.json=json;}
 public Recommendation validate(String raw,List<TimedCue> cues,long duration){
  try{
   if(raw==null||raw.length()>262144)throw new IllegalArgumentException();
   JsonNode root=json.readTree(raw);
   if(root==null||!root.isObject()||root.size()!=1||!root.path("selections").isArray()||root.path("selections").size()>10000)throw new IllegalArgumentException();
   List<Selection> groups=new ArrayList<>();
   Set<String> valid=new HashSet<>();cues.forEach(c->valid.add(c.id()));
   for(JsonNode group:root.path("selections")){
    if(!group.isObject()||group.size()!=2||!group.path("cueIds").isArray()||group.path("cueIds").isEmpty()||group.path("cueIds").size()>10000||!group.path("reason").isTextual())throw new IllegalArgumentException();
    String reason=group.path("reason").textValue().trim();
    if(reason.isEmpty()||reason.length()>1000)throw new IllegalArgumentException();
    Set<String> ids=new HashSet<>();for(JsonNode id:group.path("cueIds")){if(!id.isTextual()||!valid.contains(id.textValue()))throw new IllegalArgumentException();ids.add(id.textValue());}
    groups.add(new Selection(ordered(cues,ids),reason));
   }
   return merge(groups,cues,duration);
  }catch(Exception invalid){throw new IllegalArgumentException("AI 选段结果格式或句段编号无效");}
 }
 public Recommendation merge(List<Selection> selections,List<TimedCue> cues,long duration){
  Set<String> ids=new HashSet<>();selections.forEach(s->ids.addAll(s.cueIds()));
  var ranges=new ClipRangePlanner().plan(cues,new ArrayList<>(ids),duration);
  return new Recommendation(ordered(cues,ids),selections,ranges.stream().mapToLong(r->r.endMs()-r.startMs()).sum());
 }
 private List<String> ordered(List<TimedCue> cues,Set<String> ids){return cues.stream().filter(c->ids.contains(c.id())).sorted(Comparator.comparingLong(TimedCue::startMs).thenComparingLong(TimedCue::endMs).thenComparing(TimedCue::id)).map(TimedCue::id).toList();}
 public List<List<TimedCue>> batches(List<TimedCue> cues){
  if(cues==null||cues.isEmpty()||cues.size()>10000)throw new IllegalArgumentException("字幕数量无效");
  long total=0;for(var c:cues){if(c==null||c.id()==null||c.id().isBlank()||c.id().length()>128||c.text()==null||c.text().isBlank()||c.text().length()>12000)throw new IllegalArgumentException("单句字幕超过模型限制或格式无效");total+=c.text().length();}
  if(total>1024*1024)throw new IllegalArgumentException("字幕文本过长");
  List<List<TimedCue>> result=new ArrayList<>();int start=0;
  while(start<cues.size()){
   int end=start,chars=0;while(end<cues.size()&&end-start<100&&chars+cues.get(end).text().length()<=12000){chars+=cues.get(end++).text().length();}
   result.add(List.copyOf(cues.subList(start,end)));if(result.size()>20)throw new IllegalArgumentException("字幕超过 20 批次预算");
   if(end==cues.size())break;
   // Reduce overlap only when two prior cues would prevent any forward progress.
   int next=Math.max(start+1,end-2);while(next<end&&cues.subList(next,end).stream().mapToInt(c->c.text().length()).sum()+cues.get(end).text().length()>12000)next++;
   start=next;
  }
  return List.copyOf(result);
 }
}
