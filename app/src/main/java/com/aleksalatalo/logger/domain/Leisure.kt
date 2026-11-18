package com.aleksalatalo.logger.domain

import com.aleksalatalo.logger.data.db.ActivityEntity
import com.aleksalatalo.logger.data.db.ActivityGroupEntity

/**
 * Focus or Leisure. An activity's own setting wins; otherwise it follows its group; ungrouped
 * activities are Focus. Leisure time is kept out of Focus statistics, levels and the "all" heatmap.
 */
fun ActivityEntity.isLeisure(groups: Map<Long, ActivityGroupEntity>): Boolean =
    leisure ?: groupId?.let { groups[it]?.leisure } ?: false

fun leisureActivityIds(activities: List<ActivityEntity>, groups: List<ActivityGroupEntity>): Set<Long> {
    val byId = groups.associateBy { it.id }
    return activities.filter { it.isLeisure(byId) }.map { it.id }.toSet()
}
