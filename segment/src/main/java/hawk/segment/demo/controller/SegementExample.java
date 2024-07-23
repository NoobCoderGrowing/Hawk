package hawk.segment.demo.controller;


import hawk.segment.core.anlyzer.NShortestPathAnalyzer;
import hawk.segment.demo.DemoAnalyzer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/segment")
public class SegementExample {

    @Autowired
    DemoAnalyzer analyzer;

    @RequestMapping(value = "/NShortestPath", method = RequestMethod.POST)
    @ResponseBody
    public ArrayList getNShortestPath(@RequestBody Map<String, String> params){
        String userInput = params.get("userInput");
        int n = Integer.valueOf(params.get("NPath"));
        userInput = userInput.trim();
        return analyzer.analyze(userInput, n);
    }

    @RequestMapping(value = "/customizeDict", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Boolean> customizeDict(@RequestBody Map<String, String[]> params){
        String[] dict = params.get("dict");
        return analyzer.customizeDict(dict);
    }


    public static void main(String[] args) {
        String sentence = "我喜欢你蒋劲夫";
        NShortestPathAnalyzer analyzer = new NShortestPathAnalyzer();
//        DemoAnalyzer analyzer = new DemoAnalyzer();
//        ArrayList ret = analyzer.analyze(sentence, 3);
//        System.out.println(ret );
        System.out.println(analyzer.anlyze(sentence, "", 1));
    }
}
