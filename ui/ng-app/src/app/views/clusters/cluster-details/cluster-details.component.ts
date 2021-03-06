/*
 * Copyright (c) 2017 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

import { Component } from '@angular/core';
import { BaseDetailsComponent } from './../../../components/base/base-details.component';
import { DocumentService } from './../../../utils/document.service';
import { ActivatedRoute } from '@angular/router';
import { Links } from './../../../utils/links';

@Component({
  selector: 'app-cluster-details',
  templateUrl: './cluster-details.component.html',
  styleUrls: ['./cluster-details.component.scss']
})
export class ClusterDetailsComponent extends BaseDetailsComponent {

  constructor(route: ActivatedRoute, service: DocumentService) {
    super(route, service, Links.CLUSTERS);
  }

  protected entityInitialized() {
  }

  get showResources() {
    if (this.entity) {
      return this.entity.type == 'DOCKER';
    }
    return false;
  }
}
