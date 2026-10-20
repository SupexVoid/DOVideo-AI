package com.example.server.editor;
import com.example.server.editor.EditorModels.TimedCue;
import com.example.server.utils.DeepSeekUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.List;
@Component
public class SmartSelectionModel {
 private final DeepSeekUtils client;private final ObjectMapper json;
 public SmartSelectionModel(DeepSeekUtils client,ObjectMapper json){this.client=client;this.json=json;}
 public String select(String goal,List<TimedCue> cues){
  try{return client.smartSelection(json.writeValueAsString(new Input(goal,cues.stream().map(c->new Cue(c.id(),c.text())).toList())));}
  catch(JsonProcessingException e){throw new IllegalArgumentException("无法编码选段输入");}
 }
 private record Input(String goal,List<Cue> cues){}
 private record Cue(String id,String text){}
}
