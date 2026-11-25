import ChatKit
import ETLKit
import Foundation

// 2단계 도구 그룹: 게시판(etl_boards·etl_board_posts·etl_board_post)과 make_pdf.
// 이 파일만 고친다: 도구 정의는 `ToolSpecs.boardPdfTools`, 실행은 `Toolbox.callBoardPdf`(맡은 이름이 아니면 nil).

extension ToolSpecs {
    private static func prop(_ type: String, _ desc: String) -> ChatJSON { ["type": .string(type), "description": .string(desc)] }

    private static func boardTool(_ name: String, _ description: String, _ props: [(String, String, String)], required: [String]) -> ToolSpec {
        var o: [String: ChatJSON] = [:]
        for (key, type, desc) in props { o[key] = prop(type, desc) }
        let schema: ChatJSON = ["type": "object", "properties": .object(o), "required": .array(required.map { .string($0) })]
        return ToolSpec(name: name, description: "\(description) \(untrusted)", inputSchema: schema, readOnly: true)
    }

    /// Kotlin `EtlTools.etlTools()`의 게시판 세 도구와 `MakePdfTool.spec`(안드로이드 순서).
    static let boardPdfTools: [ToolSpec] = [
        boardTool("etl_boards", "과목 메뉴의 '게시판'(LearningX: 공지·질문·자유 게시판 등) 목록과 글 수, 안 읽은 글 수. Canvas 토론(discussion_topics)과 다르며 조교 공지나 질문 글이 여기에만 올라오는 과목이 많다.",
                  [("course_id", "integer", "과목 ID(etl_courses)")], required: ["course_id"]),
        boardTool("etl_board_posts", "게시판 하나의 글 목록(한 쪽 20개, 공지글이 위). notice=공지글, reply=답글. last_page가 page보다 크면 다음 쪽을 본다. keyword는 제목에서만 찾는다.",
                  [("course_id", "integer", "과목 ID"), ("board_id", "integer", "게시판 ID(etl_boards)"),
                   ("page", "integer", "쪽 번호. 기본 1"), ("keyword", "string", "제목 검색어(선택)")],
                  required: ["course_id", "board_id"]),
        boardTool("etl_board_post", "게시글 하나의 본문, 첨부파일 목록(이름·크기·file_id), 댓글. 필요한 글만 연다.",
                  [("course_id", "integer", "과목 ID"), ("board_id", "integer", "게시판 ID"), ("post_id", "integer", "글 ID(etl_board_posts)")],
                  required: ["course_id", "board_id", "post_id"]),
        MakePdfTool.spec,
    ]
}

extension Toolbox {
    func callBoardPdf(_ name: String, _ args: JSONObject) async throws -> Any? {
        switch name {
        case "etl_boards":
            return try await board().boards(try id(args, "course_id"))
        case "etl_board_posts":
            return try await board().posts(try id(args, "course_id"), try id(args, "board_id"), args.optInt("page", 1), args.optString("keyword", ""))
        case "etl_board_post":
            return try await board().post(try id(args, "course_id"), try id(args, "board_id"), try id(args, "post_id"))
        case MakePdfTool.NAME:
            return try makePdfTool().call(args)
        default:
            return nil
        }
    }

    /// 게시판(LearningX). eTL 토큰 해시로 LearningX 토큰을 구분한다(Kotlin `EtlTools.board()`).
    func board() throws -> BoardClient {
        guard let token = tokens() else { throw EtlException("eTL 토큰이 저장되어 있지 않습니다. 앱에서 토큰을 먼저 저장하세요.") }
        let etl = try makeClient(token)
        return BoardClient(etl, owner: BoardSession.ownerOf(token), now: { [clock] in clock().epochMilli })
    }

    func makePdfTool() -> MakePdfTool {
        MakePdfTool(workspace: workspace, userDir: userDirectory ?? EtlDownloads.userDirectory, folderLabel: userFolderLabel,
                    now: clock, relPath: { self.relPath($0) })
    }

    /// etl_download의 숨김 파일(게시판 첨부) 확인(Kotlin `EtlTools.downloadFile`의 `hidden_for_user` 갈래).
    /// LearningX는 게시판 첨부를 Canvas의 숨김 폴더에 두므로, course_id·board_id·post_id가 오고 LearningX가 그 글의 첨부임을
    /// 확인해 준 경우에만 받는다. 돌려준 글 제목을 결과의 `post`에 넣는다.
    ///
    /// 연결(`ToolboxEtl.swift`의 `downloadFile`, 이 작업 범위 밖):
    /// ```swift
    /// var post: String?
    /// if meta.optBoolean("hidden_for_user", false) { post = try await boardAttachmentPost(args, fid) }
    /// let r = try await saveAttachment(c, meta, saveToPhone)
    /// if let post { r.put("post", post) }
    /// return r
    /// ```
    func boardAttachmentPost(_ args: JSONObject, _ fileId: Int64) async throws -> String {
        if !(args.has("board_id") && args.has("post_id") && args.has("course_id")) {
            throw EtlException("숨겨진 파일입니다. 게시판 첨부라면 course_id, board_id, post_id를 함께 넘기세요. 아니면 eTL에서 확인해 주세요.")
        }
        return try await board().attachmentTitle(try id(args, "course_id"), try id(args, "board_id"), try id(args, "post_id"), fileId) ?? "게시글"
    }
}
