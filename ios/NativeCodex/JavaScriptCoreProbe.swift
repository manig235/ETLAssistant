import Foundation
import JavaScriptCore

// Run the same native JavaScript / Promise / tool callback contract on macOS
// and inside an iOS Simulator process, before connecting it to Codex.
let queue = DispatchQueue(label: "youngmin.javascript.probe")
let finished = DispatchSemaphore(value: 0)
var failure: String?
var toolCalls: [String] = []

queue.async {
    guard let context = JSContext() else {
        failure = "JavaScriptCore context creation failed"
        finished.signal()
        return
    }
    context.exceptionHandler = { _, error in
        failure = error?.toString() ?? "JavaScript exception"
        finished.signal()
    }
    let tool: @convention(block) (String, String) -> JSValue = { name, arguments in
        toolCalls.append(name)
        return JSValue(newPromiseIn: context) { resolve, reject in
            queue.asyncAfter(deadline: .now() + .milliseconds(10)) {
                do {
                    let data = try JSONSerialization.jsonObject(with: Data(arguments.utf8)) as? [String: Int]
                    guard name == "etl_sum", let input = data?["value"] else {
                        reject?.call(withArguments: ["unknown tool or invalid input"])
                        return
                    }
                    resolve?.call(withArguments: [["value": input + 1]])
                } catch { reject?.call(withArguments: [error.localizedDescription]) }
            }
        }
    }
    let done: @convention(block) (String) -> Void = { output in
        guard output == "영민이:42" else {
            failure = "Unexpected tool result: \(output)"
            finished.signal()
            return
        }
        guard toolCalls == ["etl_sum", "etl_sum"] else { failure = "Callback mismatch"; finished.signal(); return }
        guard JSContext()?.evaluateScript("typeof __nativeTool")?.toString() == "undefined" else {
            failure = "JavaScript contexts were not isolated"
            finished.signal()
            return
        }
        print("PASS: JavaScriptCore async execution, parallel native tool callbacks, Unicode and isolated contexts")
        context.exceptionHandler = nil
        context.setObject(JSValue(undefinedIn: context), forKeyedSubscript: "__nativeTool" as NSString)
        context.setObject(JSValue(undefinedIn: context), forKeyedSubscript: "__done" as NSString)
        finished.signal()
    }
    let fail: @convention(block) (String) -> Void = { error in failure = error; finished.signal() }
    context.setObject(tool, forKeyedSubscript: "__nativeTool" as NSString)
    context.setObject(done, forKeyedSubscript: "__done" as NSString)
    context.setObject(fail, forKeyedSubscript: "__fail" as NSString)
    context.evaluateScript("""
        (async () => {
            const tools = Object.freeze({ etl_sum: input => __nativeTool('etl_sum', JSON.stringify(input)) });
            const [a, b] = await Promise.all([tools.etl_sum({value: 19}), tools.etl_sum({value: 21})]);
            __done(`영민이:${a.value + b.value}`);
        })().catch(error => __fail(String(error)));
        """)
}

guard finished.wait(timeout: .now() + 20) == .success else {
    fputs("FAIL: JavaScriptCore tool callback timed out\n", stderr)
    exit(1)
}
if let failure { fputs("FAIL: \(failure)\n", stderr); exit(1) }
