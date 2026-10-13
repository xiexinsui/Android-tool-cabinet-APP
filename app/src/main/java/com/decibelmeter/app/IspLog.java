package com.decibelmeter.app;

/**
 * 日志与进度回调：STM32 下载流程运行在后台线程，所有输出都通过它送回界面。
 *
 * level 用常量区分等级，界面按等级上色（错误红、成功绿、警告黄），
 * 每行前面会再加 [时:分:秒.毫秒] 时间戳，方便对照接线/复位的先后顺序。
 * isCancelled() 让长循环（写几千页、读整片 Flash）能及时响应用户的「中止」。
 */
interface IspLog {

    int LEVEL_TRACE = 0;
    int LEVEL_INFO = 1;
    int LEVEL_CMD = 2;
    int LEVEL_WARN = 3;
    int LEVEL_ERROR = 4;
    int LEVEL_SUCCESS = 5;

    /** 进度状态用常量表示，界面再按当前语言翻译成文案。 */
    int STATUS_IDLE = 0;
    int STATUS_ERASING = 1;
    int STATUS_VERIFY_ERASE = 2;
    int STATUS_WRITING = 3;
    int STATUS_VERIFYING = 4;
    int STATUS_READING_FLASH = 5;
    int STATUS_DONE = 6;
    int STATUS_FAILED = 7;

    void log(int level, String message);

    /**
     * @param percent 0-100
     * @param status  STATUS_* 常量
     * @param detail  与语言无关的补充信息，例如 "12/300 页"、"4.5s"，可为空
     */
    void progress(int percent, int status, String detail);

    boolean isCancelled();
}
