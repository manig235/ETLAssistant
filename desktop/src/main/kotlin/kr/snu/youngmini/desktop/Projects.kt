package kr.snu.youngmini.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Project(val id: String, val name: String, val kind: String, val courseIds: List<Long> = emptyList(), val instructions: String = "") {
    fun json() = JSONObject().put("id",id).put("name",name).put("kind",kind).put("courseIds",JSONArray(courseIds)).put("instructions",instructions)
    companion object {
        val integrated = Project("integrated","통합 프로젝트","integrated")
        fun parse(o:JSONObject) = Project(o.getString("id"),o.getString("name"),o.getString("kind"),o.optJSONArray("courseIds").longs(),o.optString("instructions"))
    }
}
internal fun JSONArray?.longs(): List<Long> = if(this==null) emptyList() else (0 until length()).map {getLong(it)}

class ProjectStore(private val root: File) {
    fun load(): List<Project> = runCatching { val a=JSONArray(File(root,"projects.json").readText()); (0 until a.length()).map {Project.parse(a.getJSONObject(it))} }.getOrDefault(emptyList())
        .let { listOf(Project.integrated.copy(instructions=it.find {p->p.id=="integrated"}?.instructions.orEmpty())) + it.filter {p->p.id!="integrated"} }
    fun save(projects:List<Project>) = atomicWrite(File(root,"projects.json"),JSONArray(projects.map {it.json()}).toString().toByteArray())
    fun reconcile(projects:List<Project>,courses:List<Course>):List<Project> {
        val existing=projects.associateBy {it.id}
        val courseProjects=courses.map { c->Project("course-${c.id}",c.name,"course",listOf(c.id),existing["course-${c.id}"]?.instructions.orEmpty()) }
        // Keep old course projects that still own conversations, even while offline or a course disappears.
        return listOf(projects.find {it.id=="integrated"}?:Project.integrated)+courseProjects+projects.filter {it.kind=="custom" || it.kind=="course" && it.id !in courseProjects.map {p->p.id}}
    }
    fun custom(name:String,courseIds:List<Long>,instructions:String,id:String?=null):Project {
        require(name.trim().isNotEmpty() && name.length<=80 && instructions.length<=8000)
        return Project(id?:UUID.randomUUID().toString(),name.trim(),"custom",courseIds.filter {it>0}.distinct(),instructions)
    }
}
