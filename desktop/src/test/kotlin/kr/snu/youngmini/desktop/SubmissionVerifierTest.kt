package kr.snu.youngmini.desktop
import com.sun.net.httpserver.HttpServer
import kr.snu.etlassistant.etl.EtlClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
class SubmissionVerifierTest {
    @Test fun explicitCheckDownloadsWithoutPublishingAndDetectsSizeMismatch() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);val origin="http://127.0.0.1:${server.address.port}";var expected=4
        server.createContext("/"){exchange->val path=exchange.requestURI.path;val body=when {
            path.endsWith("/submissions/self")->"""{"workflow_state":"submitted","submission_type":"online_upload","submitted_at":"2026-11-01T00:00:00Z","attachments":[{"id":1,"display_name":"답안.pdf","size":$expected,"url":"$origin/file"}]}"""
            path=="/file"->"test"
            else->"""{"id":5,"name":"과제","due_at":"2026-12-01T00:00:00Z","submission_types":["online_upload"],"allowed_extensions":["pdf"]}"""
        };val bytes=body.toByteArray();exchange.responseHeaders.add("Content-Type",if(path=="/file")"application/pdf"else "application/json");exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)};exchange.close()};server.start()
        try {val client=EtlClient("fixture-token",origin);val ok=SubmissionVerifier.verify(client,10,5);assertEquals("ok",ok.getString("verdict"));assertTrue(ok.getJSONArray("files").getJSONObject(0).getBoolean("size_ok"));assertEquals(4,ok.getJSONArray("files").getJSONObject(0).getInt("bytes"));expected=8;val mismatch=SubmissionVerifier.verify(client,10,5);assertEquals("problem",mismatch.getString("verdict"));assertTrue(mismatch.getJSONArray("problems").toString().contains("크기"))}finally{server.stop(0)}
    }
}
