package kr.snu.youngmini.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kr.snu.etlassistant.codex.ModelChoice
import kr.snu.etlassistant.codex.ModelInfo
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.imageio.ImageIO

class ImageAttachmentsTest {
    private fun image(paths:AppPaths):Artifact {
        val id=UUID.randomUUID().toString()
        val file=File(paths.workspace,"uploads/$id.png").apply {parentFile.mkdirs()}
        ImageIO.write(BufferedImage(12,8,BufferedImage.TYPE_INT_RGB),"png",file)
        return Artifact(id,"문제 사진.png","uploads/$id.png",file.length(),"attachment")
    }
    @Test fun imageDraftAndSentCardSurviveHistoryRoundTrip() {
        val root=Files.createTempDirectory("youngmini-image-history").toFile()
        try {
            val paths=AppPaths(root);val photo=image(paths);ImageAttachments(paths.workspace).validate(listOf(photo))
            val chat=ChatSession(draftImages=listOf(photo));val history=ChatHistory(paths.chats)
            history.save(chat.saved());assertEquals(listOf(photo),ChatSession.from(history.list().single()).draftImages)
            val sent=photo.copy(messageId="question")
            history.save(chat.copy(draftImages=emptyList(),artifacts=listOf(sent),messages=listOf(ChatMessage("question","user",""))).saved())
            val restored=ChatSession.from(history.list().single());assertTrue(restored.draftImages.isEmpty());assertEquals("question",restored.artifacts.single().messageId)
        } finally {root.deleteRecursively()}
    }
    @Test fun untrustedPathsNonImagesAndTooManyPhotosAreRejected() {
        val root=Files.createTempDirectory("youngmini-image-boundary").toFile()
        try {
            val paths=AppPaths(root);val photo=image(paths);val store=ImageAttachments(paths.workspace)
            fun rejects(photos:List<Artifact>) {try {store.validate(photos);fail("accepted invalid image")}catch(_:Exception){}}
            rejects(listOf(photo.copy(path="../secret.png")));rejects(listOf(photo.copy(kind="requested")));rejects(listOf(photo,photo))
            rejects((1..6).map {image(paths)})
            val file=File(paths.workspace,photo.path);file.writeText("not an image");rejects(listOf(photo.copy(bytes=file.length())))
        } finally {root.deleteRecursively()}
    }
    private class Backend:ChatBackend {
        override var onNotification:(String,JSONObject)->Unit={_,_->};override var onClosed:()->Unit={}
        override val authWarning:String?=null
        @Volatile var received:JSONObject?=null
        override fun start(progress:(String)->Unit) {}
        override fun loggedIn()=true
        override fun models()=emptyList<ModelInfo>()
        override fun login(device:Boolean,prompt:(String,String?)->Unit) {}
        override fun cancelLogin() {};override fun logout() {};override fun close() {}
        override fun thread(saved:String?,choice:ModelChoice,instructions:String)=saved?:"image-thread"
        override fun send(thread:String,text:String,choice:ModelChoice)="turn"
        override fun sendImages(thread:String,text:String,choice:ModelChoice,images:List<File>):String {received=DesktopEngine.turnParams(thread,text,choice,images);return "turn"}
        override fun interrupt(thread:String,turn:String) {}
    }
    @Test fun imageOnlyQuestionUsesLocalImageAndStaysInItsOwningSession() = runBlocking {
        val root=Files.createTempDirectory("youngmini-image-flow").toFile();val paths=AppPaths(root);val backend=Backend();val app=AppController(paths,backend=backend)
        try {
            withContext(Dispatchers.Main){app.start()};withTimeout(3000){app.state.first {it.ready}}
            val owner=app.state.value.activeChatId;val photo=image(paths)
            withContext(Dispatchers.Main){app.attachImages(owner,listOf(photo));app.send(owner,"");app.newChat()}
            withTimeout(3000){while(backend.received==null)delay(10)}
            val input=backend.received!!.getJSONArray("input");assertEquals(2,input.length());assertEquals("localImage",input.getJSONObject(1).getString("type"))
            assertEquals(File(paths.workspace,photo.path).absolutePath,input.getJSONObject(1).getString("path"))
            val chat=app.state.value.sessions.single {it.id==owner};assertTrue(chat.draftImages.isEmpty());assertEquals(chat.messages.single().id,chat.artifacts.single().messageId)
            assertTrue(app.state.value.sessions.single {it.id!=owner}.artifacts.isEmpty());assertEquals(photo.id,app.artifact(photo.id)!!.id);assertTrue(app.artifactFile(photo.id).isFile)
            val encoded=StateJson.encode(app.state.value).getJSONArray("sessions");assertEquals(2,encoded.length())
        } finally {withContext(Dispatchers.Main){app.close()};root.deleteRecursively()}
    }
}
