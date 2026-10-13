package hawk.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SearchController {

    /** topK 上限。再大对演示没有意义，只会让响应体变重。 */
    private static final int MAX_TOP_K = 100;

    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    /** 首屏要显示的索引概况。前端用它填页眉，也用来判断后端是否就绪。 */
    @GetMapping("/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalDocs", service.totalDocs());
        return m;
    }

    @GetMapping("/search/fulltext")
    public ResponseEntity<?> fullText(@RequestParam(name = "q") String q,
                                      @RequestParam(name = "topK", defaultValue = "20") int topK) {
        String query = q == null ? "" : q.trim();
        if (query.isEmpty()) {
            return badRequest("请输入查询词。");
        }
        return ResponseEntity.ok(service.searchFullText(query, clamp(topK)));
    }

    private static int clamp(int topK) {
        if (topK < 1) {
            return 1;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
