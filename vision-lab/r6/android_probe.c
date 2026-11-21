/* Lab-only smoke test. Read consecutive NCHW float windows and return logits. */
#include <stdio.h>
#include <stdlib.h>
#include <onnxruntime_c_api.h>

enum { ELEMENTS = 3 * 160 * 192 };
static const OrtApi *api;

static void check(OrtStatus *status) {
    if (status) {
        fprintf(stderr, "%s\n", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        exit(EXIT_FAILURE);
    }
}

int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "Usage: android_probe model.onnx windows.f32 logits.f32\n");
        return EXIT_FAILURE;
    }
    FILE *input = fopen(argv[2], "rb");
    FILE *output = fopen(argv[3], "wb");
    if (!input || !output) return EXIT_FAILURE;
    api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (!api) return EXIT_FAILURE;
    OrtEnv *env;
    OrtSessionOptions *options;
    OrtSession *session;
    OrtMemoryInfo *memory;
    check(api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "r6", &env));
    check(api->CreateSessionOptions(&options));
    check(api->SetIntraOpNumThreads(options, 2));
    check(api->CreateSession(env, argv[1], options, &session));
    check(api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memory));

    const int64_t shape[] = {1, 3, 160, 192};
    const char *inputs[] = {"rgb"}, *outputs[] = {"logits"};
    static float pixels[ELEMENTS];
    size_t bytes_read;
    unsigned count = 0;
    while ((bytes_read = fread(pixels, 1, sizeof(pixels), input)) != 0) {
        if (bytes_read != sizeof(pixels)) return EXIT_FAILURE;
        OrtValue *tensor, *result = NULL;
        check(api->CreateTensorWithDataAsOrtValue(memory, pixels, sizeof(pixels), shape, 4,
                                                   ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &tensor));
        const OrtValue *batch[] = {tensor};
        check(api->Run(session, NULL, inputs, batch, 1, outputs, 1, &result));
        OrtTensorTypeAndShapeInfo *info;
        size_t elements;
        ONNXTensorElementDataType element_type;
        check(api->GetTensorTypeAndShape(result, &info));
        check(api->GetTensorShapeElementCount(info, &elements));
        check(api->GetTensorElementType(info, &element_type));
        if (elements != ELEMENTS || element_type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) return EXIT_FAILURE;
        void *logits;
        check(api->GetTensorMutableData(result, &logits));
        if (fwrite(logits, 1, sizeof(pixels), output) != sizeof(pixels)) return EXIT_FAILURE;
        api->ReleaseTensorTypeAndShapeInfo(info);
        api->ReleaseValue(result);
        api->ReleaseValue(tensor);
        count++;
    }
    int ok = !ferror(input) && count > 0;
    fclose(input);
    if (fclose(output) != 0) ok = 0;
    api->ReleaseMemoryInfo(memory);
    api->ReleaseSession(session);
    api->ReleaseSessionOptions(options);
    api->ReleaseEnv(env);
    printf("%s %u windows\n", OrtGetApiBase()->GetVersionString(), count);
    return ok ? EXIT_SUCCESS : EXIT_FAILURE;
}
