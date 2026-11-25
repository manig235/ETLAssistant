// Opaque, owned handles keep QuickJS's platform-dependent JSValue ABI in C.
// Only the engine core is linked. No libc JS module, loader, filesystem or JIT.
#include "quickjs.h"
#include <stdlib.h>
#include <string.h>
typedef struct { JSContext *ctx; JSValue value; } YMValue;
typedef YMValue *(*YMNative)(JSContext *, int, const YMValue *const *);
static YMValue *owned(JSContext *ctx, JSValue value) {
    if (JS_IsException(value)) return NULL;
    YMValue *out = malloc(sizeof(*out));
    if (!out) { JS_FreeValue(ctx, value); JS_ThrowOutOfMemory(ctx); return NULL; }
    *out = (YMValue){ctx, value};
    return out;
}
void ym_free(YMValue *v) { if (v) { JS_FreeValue(v->ctx,v->value); free(v); } }
YMValue *ym_dup(const YMValue *v) { return owned(v->ctx, JS_DupValue(v->ctx,v->value)); }
JSRuntime *ym_runtime(void) { return JS_NewRuntime(); }
void ym_runtime_free(JSRuntime *rt) { JS_FreeRuntime(rt); }
JSContext *ym_context(JSRuntime *rt) { return JS_NewContext(rt); }
void ym_context_free(JSContext *ctx) { JS_FreeContext(ctx); }
void ym_limits(JSRuntime *rt, int (*interrupt)(JSRuntime *, void *), void *opaque) {
    JS_SetMemoryLimit(rt,64 * 1024 * 1024);
    JS_SetMaxStackSize(rt,1024 * 1024);
    JS_SetInterruptHandler(rt,interrupt,opaque);
    JS_SetCanBlock(rt,0);
}
YMValue *ym_string(JSContext *ctx,const char *text,size_t len) { return owned(ctx,JS_NewStringLen(ctx,text,len)); }
char *ym_text(const YMValue *v,size_t *len) { return (char *)JS_ToCStringLen(v->ctx,len,v->value); }
void ym_text_free(JSContext *ctx,char *text) { JS_FreeCString(ctx,text); }
int ym_number(const YMValue *v,double *out) { return JS_ToFloat64(v->ctx,out,v->value); }
int ym_null(const YMValue *v) { return JS_IsNull(v->value); }
int ym_function(const YMValue *v) { return JS_IsFunction(v->ctx,v->value); }
YMValue *ym_undefined(JSContext *ctx) { return owned(ctx,JS_UNDEFINED); }
YMValue *ym_null_value(JSContext *ctx) { return owned(ctx,JS_NULL); }
YMValue *ym_number_value(JSContext *ctx,double value) { return owned(ctx,JS_NewFloat64(ctx,value)); }
static YMValue *parse(JSContext *ctx,const char *text,size_t len,int json) {
    // Both parsers require a trailing NUL even with an explicit input length.
    char *buffer=malloc(len+1);
    if (!buffer) { JS_ThrowOutOfMemory(ctx); return NULL; }
    memcpy(buffer,text,len); buffer[len]=0;
    JSValue result=json ? JS_ParseJSON(ctx,buffer,len,"tool-result") : JS_Eval(ctx,buffer,len,"youngmin-cell",JS_EVAL_TYPE_GLOBAL);
    free(buffer); return owned(ctx,result);
}
YMValue *ym_json(JSContext *ctx,const char *text,size_t len) { return parse(ctx,text,len,1); }
YMValue *ym_eval(JSContext *ctx,const char *text,size_t len) { return parse(ctx,text,len,0); }
YMValue *ym_exception(JSContext *ctx) { return owned(ctx,JS_GetException(ctx)); }
YMValue *ym_error(JSContext *ctx,const char *text) { JS_ThrowInternalError(ctx,"%s",text); return NULL; }
YMValue *ym_call(const YMValue *fn,int count,const YMValue *const *args) {
    if (count > 8) return ym_error(fn->ctx,"Too many callback arguments");
    JSValue values[8];
    for (int i=0;i<count;i++) values[i]=args[i]->value;
    return owned(fn->ctx,JS_Call(fn->ctx,fn->value,JS_UNDEFINED,count,values));
}
YMValue *ym_promise(JSContext *ctx,YMValue **resolve,YMValue **reject) {
    JSValue functions[2];
    JSValue promise=JS_NewPromiseCapability(ctx,functions);
    if (JS_IsException(promise)) return NULL;
    *resolve=owned(ctx,functions[0]); *reject=owned(ctx,functions[1]);
    if (!*resolve || !*reject) { ym_free(*resolve); ym_free(*reject); JS_FreeValue(ctx,promise); return NULL; }
    return owned(ctx,promise);
}
static JSValue native(JSContext *ctx,JSValueConst self,int argc,JSValueConst *argv) {
    if (argc > 8) return JS_ThrowInternalError(ctx,"Too many native arguments");
    YMValue args[8]; const YMValue *pointers[8];
    for (int i=0;i<argc;i++) { args[i]=(YMValue){ctx,argv[i]}; pointers[i]=&args[i]; }
    YMNative callback=(YMNative)JS_GetContextOpaque(ctx);
    YMValue *out=callback(ctx,argc,pointers);
    if (!out) return JS_EXCEPTION;
    JSValue value=out->value; free(out); return value;
}
int ym_install(JSContext *ctx,YMNative callback) {
    JS_SetContextOpaque(ctx,(void *)callback);
    JSValue global=JS_GetGlobalObject(ctx);
    int result=JS_SetPropertyStr(ctx,global,"__ymNative",JS_NewCFunction(ctx,native,"native",3));
    JS_FreeValue(ctx,global); return result;
}
// Returns 1 after one job, 0 when idle and -1 with an exception in the cell context.
int ym_job(JSRuntime *rt) { JSContext *ctx; return JS_ExecutePendingJob(rt,&ctx); }
