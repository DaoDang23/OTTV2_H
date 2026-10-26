package ottv2.worker.compile;

@FunctionalInterface
interface BotCompiler {
    CompileOutcome compile(CompileJob job, byte[] source) throws Exception;
}
