package com.nekonyan.assistant.core.annot

/**
 * 内置 COCO 80 类的**中文名**（下标与 COCO 的 class id 严格一一对应）。
 *
 * 为什么要它：内置模型（yolo11n，COCO 80 类）的类别名是英文的，
 * 标注页上那一排 person/bicycle/car… 对中文用户不友好。
 *
 * 判定"是不是内置 COCO"用的是**完整名单比对**，而不是只看数量 ——
 * 用户自己导入的模型很可能也是 80 类（比如从 COCO 微调来的），
 * 光看数量会把他的中文名硬换成我们的译文，那是帮倒忙。
 */
object CocoZh {

    val EN = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
        "toothbrush"
    )

    val ZH = listOf(
        "人", "自行车", "汽车", "摩托车", "飞机", "公交车", "火车", "卡车", "船",
        "红绿灯", "消防栓", "停车标志", "停车计时器", "长椅", "鸟", "猫",
        "狗", "马", "羊", "牛", "大象", "熊", "斑马", "长颈鹿", "背包",
        "雨伞", "手提包", "领带", "行李箱", "飞盘", "双板滑雪", "单板滑雪", "球",
        "风筝", "棒球棒", "棒球手套", "滑板", "冲浪板", "网球拍",
        "瓶子", "酒杯", "杯子", "叉子", "刀", "勺子", "碗", "香蕉", "苹果",
        "三明治", "橙子", "西兰花", "胡萝卜", "热狗", "披萨", "甜甜圈", "蛋糕", "椅子",
        "沙发", "盆栽", "床", "餐桌", "马桶", "电视", "笔记本电脑", "鼠标",
        "遥控器", "键盘", "手机", "微波炉", "烤箱", "烤面包机", "水槽",
        "冰箱", "书", "时钟", "花瓶", "剪刀", "泰迪熊", "吹风机",
        "牙刷"
    )

    /**
     * 把类别名本地化：**只有整份名单就是 COCO 80（英文、顺序一致）时才翻译**，
     * 其余情况原样返回（用户自定义/自己训练的模型，名字是他自己的，不要动）。
     */
    fun localize(names: List<String>): List<String> {
        if (names.size != EN.size) return names
        val same = names.withIndex().all { (i, n) -> n.trim().equals(EN[i], ignoreCase = true) }
        return if (same) ZH else names
    }

    /** 中文名是否可用（防御：两张表长度必须一致，否则宁可不用） */
    fun isUsable(): Boolean = EN.size == ZH.size && EN.size == 80
}
