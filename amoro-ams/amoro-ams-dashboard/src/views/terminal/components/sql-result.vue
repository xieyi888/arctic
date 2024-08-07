<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
/ -->

<script lang="ts" setup>
import { computed } from 'vue'
import type { IDebugResult } from '@/types/common.type'
import { debugResultBgcMap } from '@/types/common.type'

const props = defineProps<{ info: IDebugResult }>()

const isEmpty = computed(() => {
  return !props.info || !props.info?.columns
})

const status = computed(() => {
  return props.info?.status
})
</script>

<template>
  <!-- :style="{width: `${innerWidth}px`}" -->
  <div class="sql-result-wrap">
    <div class="result-status" :style="{ background: debugResultBgcMap[status as keyof typeof debugResultBgcMap] }">
      <template v-if="status === 'Running'">
        <loading-outlined style="color: #1890ff" />
      </template>
      <template v-if="status === 'Canceled' || status === 'Failed'">
        <close-circle-outlined style="color: #ff4d4f" />
      </template>
      <template v-if="status === 'Finished'">
        <check-circle-outlined style="color: #52c41a" />
      </template>
      <template v-if="status === 'Created'">
        <close-circle-outlined style="color:#333" />
      </template>
      <span class="g-ml-8">{{ $t(status) }}</span>
    </div>
    <div v-if="isEmpty" class="empty">
      {{ $t('noResult') }}
    </div>
    <div v-else class="result-wrap">
      <table class="ant-table sql-result-table" style="width:100%">
        <thead class="ant-table-thead">
          <tr>
            <th v-for="item in props.info.columns" :key="item">
              {{ item }}
            </th>
          </tr>
        </thead>
        <tbody class="ant-table-tbody">
          <tr v-for="(rowItem, row) in props.info.rowData" :key="row + 1">
            <td v-for="(val, col) in rowItem" :key="`${row}${val}${col}`">
              <span class="td-val" :title="val || ''">{{ val }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style lang="less" scoped>
.sql-result-wrap {
  height: 100%;
  width: 100%;
  overflow: auto;
  box-sizing: border-box;
  .result-status {
    padding: 4px 12px;
  }
  .empty {
    padding: 6px 12px;
    color: #79809a;
  }
  .result-wrap {
    padding: 6px 12px;
    overflow-x: auto;
  }
  .sql-result-table {
    height: 100%;
    overflow: auto;
    .ant-table-tbody {
      td {
        .td-val{
          overflow: hidden;
          white-space: nowrap;
          text-overflow: ellipsis;
          max-width: 240px !important;
          display: inherit;
          // display: inline-block;
          // display: table-cell;
          // min-height: 38px;
          // height: 38px;
        }
      }
    }
  }
}
</style>
